// Modified by SignalFx
package datadog.trace.instrumentation.netty40.server;

import static datadog.trace.instrumentation.netty40.server.NettyHttpServerDecorator.DECORATE;

import datadog.trace.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.netty40.AttributeKeys;
import datadog.trace.instrumentation.netty40.NettyUtils;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpResponse;

public class HttpServerResponseTracingHandler extends ChannelOutboundHandlerAdapter {

  @Override
  public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise prm) {
    final AgentSpan span = ctx.channel().attr(AttributeKeys.SERVER_ATTRIBUTE_KEY).get();
    if (span == null || !(msg instanceof HttpResponse)) {
      ctx.write(msg, prm);
      return;
    }

    final HttpResponse response = (HttpResponse) msg;

    try {
      ctx.write(msg, prm);
    } catch (final Throwable throwable) {
      DECORATE.onError(span, throwable);
      try {
        int status = response.getStatus().code();
        NettyUtils.setServerSpanHttpStatus(span, status);
      } catch (final Throwable exc) {
        // Unable to retrieve status code
      }
      span.finish(); // Finish the span manually since finishSpanOnClose was false
      throw throwable;
    }

    NettyUtils.setServerSpanHttpStatus(span, response.getStatus().code());
    DECORATE.beforeFinish(span);
    span.finish(); // Finish the span manually since finishSpanOnClose was false
  }
}
