// Modified by SignalFx
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.api.DDSpanTypes
import datadog.trace.instrumentation.api.Tags
import datadog.trace.instrumentation.netty40.NettyUtils
import datadog.trace.instrumentation.netty40.server.NettyHttpServerDecorator
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelPipeline
import io.netty.channel.EventLoopGroup
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpHeaders
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpRequestDecoder
import io.netty.handler.codec.http.HttpResponseEncoder
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import io.netty.util.CharsetUtil

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.UNAVAILABLE
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_LENGTH
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1

class Netty40ServerTest extends HttpServerTest<EventLoopGroup, NettyHttpServerDecorator> {

  @Override
  EventLoopGroup startServer(int port) {
    def eventLoopGroup = new NioEventLoopGroup()

    ServerBootstrap bootstrap = new ServerBootstrap()
      .group(eventLoopGroup)
      .handler(new LoggingHandler(LogLevel.INFO))
      .childHandler([
        initChannel: { ch ->
          ChannelPipeline pipeline = ch.pipeline()
          def handlers = [new HttpRequestDecoder(), new HttpResponseEncoder()]
          handlers.each { pipeline.addLast(it) }
          pipeline.addLast([
            channelRead0       : { ctx, msg ->
              if (msg instanceof HttpRequest) {
                ServerEndpoint endpoint = ServerEndpoint.forPath((msg as HttpRequest).uri)
                ctx.write controller(endpoint) {
                  ByteBuf content = null
                  FullHttpResponse response = null
                  switch (endpoint) {
                    case SUCCESS:
                    case ERROR:
                      content = Unpooled.copiedBuffer(endpoint.body, CharsetUtil.UTF_8)
                      response = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.valueOf(endpoint.status), content)
                      break
                    case REDIRECT:
                      response = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.valueOf(endpoint.status))
                      response.headers().set(HttpHeaders.Names.LOCATION, endpoint.body)
                      break
                    case EXCEPTION:
                      throw new Exception(endpoint.body)
                    case UNAVAILABLE:
                      content = Unpooled.copiedBuffer(endpoint.body, CharsetUtil.UTF_8)
                      response = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.valueOf(endpoint.status), content)
                      break
                    default:
                      content = Unpooled.copiedBuffer(NOT_FOUND.body, CharsetUtil.UTF_8)
                      response = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.valueOf(NOT_FOUND.status), content)
                      break
                  }
                  response.headers().set(CONTENT_TYPE, "text/plain")
                  if (content) {
                    response.headers().set(CONTENT_LENGTH, content.readableBytes())
                  }
                  return response
                }
              }
            },
            exceptionCaught    : { ChannelHandlerContext ctx, Throwable cause ->
              ByteBuf content = Unpooled.copiedBuffer(cause.message, CharsetUtil.UTF_8)
              FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR, content)
              response.headers().set(CONTENT_TYPE, "text/plain")
              response.headers().set(CONTENT_LENGTH, content.readableBytes())
              ctx.write(response)
            },
            channelReadComplete: { it.flush() }
          ] as SimpleChannelInboundHandler)
        }
      ] as ChannelInitializer).channel(NioServerSocketChannel)
    bootstrap.bind(port).sync()

    return eventLoopGroup
  }

  @Override
  void stopServer(EventLoopGroup server) {
    server?.shutdownGracefully()
  }

  @Override
  NettyHttpServerDecorator decorator() {
    NettyHttpServerDecorator.DECORATE
  }

  @Override
  String expectedOperationName() {
    "netty.request"
  }

  def "test #responseCode statusCode rewrite #rewrite"() {
    def property = "signalfx.${NettyUtils.NETTY_REWRITTEN_SERVER_STATUS_PREFIX}${endpoint.status}"
    System.getProperties().setProperty(property, "$rewrite")

    def request = request(endpoint, "GET", null).build()
    def response = client.newCall(request).execute()

    expect:
    response.code() == endpoint.status

    and:
    cleanAndAssertTraces(1) {
      trace(0, 2) {
        span(0) {
          spanType DDSpanTypes.HTTP_SERVER
          errored error
          tags {
            "$Tags.COMPONENT" "netty"
            "$Tags.HTTP_METHOD" "GET"
            if (rewrite) {
              "$Tags.HTTP_STATUS" null
              "$NettyUtils.ORIG_HTTP_STATUS" endpoint.status
            } else {
              "$Tags.HTTP_STATUS" endpoint.status
            }
            "$Tags.HTTP_URL" "${endpoint.resolve(address)}"
            "$Tags.PEER_HOSTNAME" "localhost"
            "$Tags.PEER_HOST_IPV4" "127.0.0.1"
            "$Tags.PEER_PORT" Integer
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            if (error) {
              tag("error", true)
            }
            defaultTags()
          }
        }
        controllerSpan(it, 1, span(0))
      }
    }

    where:
    endpoint | error | rewrite
    SUCCESS | false | false
    SUCCESS | false | true
    ERROR | true  | false
    ERROR | false | true
    UNAVAILABLE | true  | false
    UNAVAILABLE | false | true
  }
}
