/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.remoting.transport.netty4;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.exchange.Response;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.timeout.IdleStateEvent;

/**
 * NettyClientHandler
 */
@io.netty.channel.ChannelHandler.Sharable
public class NettyClientHandler extends ChannelDuplexHandler {
    private static final Logger logger = LoggerFactory.getLogger(NettyClient.class);

    private final URL url;

    private final ChannelHandler handler;

    public NettyClientHandler(URL url, ChannelHandler handler) {
        if (url == null) {
            throw new IllegalArgumentException("url == null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler == null");
        }
        this.url = url; // 仅仅是保存相关参数到属性中
        this.handler = handler; // NettyClient 内部包含了真正的 handler
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);   // 尝试从 CHANNEL_MAP 中获取 netty 原生 channel 的装饰者 channel，如果没有获取到，那么就直接新建一个
        try {   // handler: NettyClient -> MultiMessageHandler -> HeartBeatHandler -> AllChannelHandler -> （AllChannelHandler 持有）DecodeHandler -> HeaderExchangeHandler -> DubboProtocol$1
            handler.connected(channel);
        } finally {
            NettyChannel.removeChannelIfDisconnected(ctx.channel());
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
        try {  // handler: NettyClient -> MultiMessageHandler -> HeartBeatHandler -> AllChannelHandler -> （AllChannelHandler 持有）DecodeHandler -> HeaderExchangeHandler -> DubboProtocol$1
            handler.disconnected(channel);
        } finally {
            NettyChannel.removeChannelIfDisconnected(ctx.channel());
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
        try {   // handler: NettyClient -> MultiMessageHandler -> HeartBeatHandler -> AllChannelHandler -> （AllChannelHandler 持有）DecodeHandler -> HeaderExchangeHandler -> DubboProtocol$1
            handler.received(channel, msg);
        } finally {
            NettyChannel.removeChannelIfDisconnected(ctx.channel());
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        super.write(ctx, msg, promise);
        final NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
        final boolean isRequest = msg instanceof Request;

        // We add listeners to make sure our out bound event is correct.
        // If our out bound event has an error (in most cases the encoder fails),
        // we need to have the request return directly instead of blocking the invoke process.
        promise.addListener(future -> {
            try {
                if (future.isSuccess()) {
                    // if our future is success, mark the future to sent.此方法貌似没有干什么重要的事情
                    handler.sent(channel, msg); // handler: NettyClient -> MultiMessageHandler -> HeartBeatHandler -> AllChannelHandler
                    return;
                }

                Throwable t = future.cause();
                if (t != null && isRequest) {
                    Request request = (Request) msg;
                    Response response = buildErrorResponse(request, t);
                    handler.received(channel, response);
                }
            } finally {
                NettyChannel.removeChannelIfDisconnected(ctx.channel());
            }
        });
    }

    @Override   // 心跳消息的传播
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            try {   // handler: NettyClient -> MultiMessageHandler -> HeartBeatHandler -> AllChannelHandler -> （AllChannelHandler 持有）DecodeHandler -> HeaderExchangeHandler -> DubboProtocol$1
                NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
                if (logger.isDebugEnabled()) {
                    logger.debug("IdleStateEvent triggered, send heartbeat to channel " + channel);
                }
                Request req = new Request();
                req.setVersion(Version.getProtocolVersion());   // 2.0.2
                req.setTwoWay(true);
                req.setEvent(Request.HEARTBEAT_EVENT);
                channel.send(req);
            } finally {
                NettyChannel.removeChannelIfDisconnected(ctx.channel());
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    @Override   // 全局的异常处理
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
            throws Exception {  // handler: NettyClient -> MultiMessageHandler -> HeartBeatHandler -> AllChannelHandler -> （AllChannelHandler 持有）DecodeHandler -> HeaderExchangeHandler -> DubboProtocol$1
        NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
        try {   // handler: NettyClient -> MultiMessageHandler -> HeartBeatHandler -> AllChannelHandler -> （AllChannelHandler 持有）DecodeHandler -> HeaderExchangeHandler -> DubboProtocol$1
            handler.caught(channel, cause);
        } finally {
            NettyChannel.removeChannelIfDisconnected(ctx.channel());
        }
    }

    /**
     * build a bad request's response
     *
     * @param request the request
     * @param t       the throwable. In most cases, serialization fails.
     * @return the response
     */
    private static Response buildErrorResponse(Request request, Throwable t) {
        Response response = new Response(request.getId(), request.getVersion());
        response.setStatus(Response.BAD_REQUEST);
        response.setErrorMessage(StringUtils.toString(t));
        return response;
    }
}
