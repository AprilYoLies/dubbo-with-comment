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
package org.apache.dubbo.remoting.exchange.support.header;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.constants.RemotingConstants;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.ExecutionException;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.exchange.ExchangeChannel;
import org.apache.dubbo.remoting.exchange.ExchangeHandler;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.exchange.Response;
import org.apache.dubbo.remoting.exchange.support.DefaultFuture;
import org.apache.dubbo.remoting.transport.ChannelHandlerDelegate;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;


/**
 * ExchangeReceiver
 */
public class HeaderExchangeHandler implements ChannelHandlerDelegate {

    protected static final Logger logger = LoggerFactory.getLogger(HeaderExchangeHandler.class);

    public static final String KEY_READ_TIMESTAMP = HeartbeatHandler.KEY_READ_TIMESTAMP;

    public static final String KEY_WRITE_TIMESTAMP = HeartbeatHandler.KEY_WRITE_TIMESTAMP;
    // handler 为 DubboProtocol 类中的 ExchangeHandlerAdapter 匿名内部类，需要关注此 handler 是如何被初始化的
    private final ExchangeHandler handler;

    public HeaderExchangeHandler(ExchangeHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("handler == null");
        }
        this.handler = handler;
    }

    // 此方法主要是从 DefaultFuture 的 FUTURES 中获取 response id 对应的 DefaultFuture，然后将 response 赋值为 DefaultFuture 的 response 属性
    static void handleResponse(Channel channel, Response response) throws RemotingException {
        if (response != null && !response.isHeartbeat()) {
            DefaultFuture.received(channel, response);
        }
    }

    private static boolean isClientSide(Channel channel) {
        InetSocketAddress address = channel.getRemoteAddress();
        URL url = channel.getUrl();
        return url.getPort() == address.getPort() &&
                NetUtils.filterLocalHost(url.getIp())
                        .equals(NetUtils.filterLocalHost(address.getAddress().getHostAddress()));
    }

    void handlerEvent(Channel channel, Request req) throws RemotingException {
        if (req.getData() != null && req.getData().equals(Request.READONLY_EVENT)) {
            channel.setAttribute(RemotingConstants.CHANNEL_ATTRIBUTE_READONLY_KEY, Boolean.TRUE);
        }
    }

    void handleRequest(final ExchangeChannel channel, Request req) throws RemotingException {   // 注意是在 req 的 twoWay 属性值为 true 才执行当前方法
        Response res = new Response(req.getId(), req.getVersion()); // 构建 response
        if (req.isBroken()) {   // 这里是 req 的解码出现异常的处理过程，收到的消息有误
            Object data = req.getData();

            String msg;
            if (data == null) { // 根据不同的 data，构建 msg 信息
                msg = null;
            } else if (data instanceof Throwable) {
                msg = StringUtils.toString((Throwable) data);
            } else {
                msg = data.toString();
            }
            res.setErrorMessage("Fail to decode request due to: " + msg);
            res.setStatus(Response.BAD_REQUEST);

            channel.send(res);  // 将错误的响应发送给客户端
            return;
        }
        // find handler by message class.
        Object msg = req.getData();
        try {   // 执行到这里，就说明消息的解码是成功的
            // handle data.
            CompletableFuture<Object> future = handler.reply(channel, msg); // 这里返回的是 Service 实现类调用对应方法后的结果
            if (future.isDone()) {
                res.setStatus(Response.OK); // 设置响应消息的状态
                res.setResult(future.get());    // 设置响应的结果
                channel.send(res);
                return;
            }
            future.whenComplete((result, t) -> {
                try {
                    if (t == null) {
                        res.setStatus(Response.OK);
                        res.setResult(result);
                    } else {
                        res.setStatus(Response.SERVICE_ERROR);
                        res.setErrorMessage(StringUtils.toString(t));
                    }
                    channel.send(res);
                } catch (RemotingException e) {
                    logger.warn("Send result to consumer failed, channel is " + channel + ", msg is " + e);
                } finally {
                    // HeaderExchangeChannel.removeChannelIfDisconnected(channel);
                }
            });
        } catch (Throwable e) {
            res.setStatus(Response.SERVICE_ERROR);
            res.setErrorMessage(StringUtils.toString(e));
            channel.send(res);
        }
    }

    @Override   // 对于连接消息，此 handler 就是为 channel 添加两个时间戳属性
    public void connected(Channel channel) throws RemotingException {
        // 添加 READ_TIMESTAMP 属性，值为当前时间戳
        channel.setAttribute(KEY_READ_TIMESTAMP, System.currentTimeMillis());
        // 添加 WRITE_TIMESTAMP 属性，值为当前时间戳
        channel.setAttribute(KEY_WRITE_TIMESTAMP, System.currentTimeMillis());
        // 获取 channel 中的 HeaderExchangeChannel，没有的话就新建一个
        ExchangeChannel exchangeChannel = HeaderExchangeChannel.getOrAddChannel(channel);
        try {   // handler: NettyClient -> MultiMessageHandler -> HeartBeatHandler -> AllChannelHandler -> （AllChannelHandler 持有）DecodeHandler -> HeaderExchangeHandler -> DubboProtocol$1
            handler.connected(exchangeChannel); // 本方法主要是根据 url 的 on-connection 参数构建对应的 invocation，再调用 reply 方法
        } finally {
            HeaderExchangeChannel.removeChannelIfDisconnected(channel);
        }
    }

    @Override
    public void disconnected(Channel channel) throws RemotingException {
        channel.setAttribute(KEY_READ_TIMESTAMP, System.currentTimeMillis());
        channel.setAttribute(KEY_WRITE_TIMESTAMP, System.currentTimeMillis());
        ExchangeChannel exchangeChannel = HeaderExchangeChannel.getOrAddChannel(channel);
        try {
            handler.disconnected(exchangeChannel);
        } finally {
            DefaultFuture.closeChannel(channel);
            HeaderExchangeChannel.removeChannelIfDisconnected(channel);
        }
    }

    @Override
    public void sent(Channel channel, Object message) throws RemotingException {
        Throwable exception = null;
        try {
            channel.setAttribute(KEY_WRITE_TIMESTAMP, System.currentTimeMillis());  // WRITE_TIMESTAMP -> 当前时间戳
            ExchangeChannel exchangeChannel = HeaderExchangeChannel.getOrAddChannel(channel);   // HeaderExchangeChannel 就是对 NettyChannel 进行了包装而已
            try {
                handler.sent(exchangeChannel, message);
            } finally {
                HeaderExchangeChannel.removeChannelIfDisconnected(channel);
            }
        } catch (Throwable t) {
            exception = t;
        }
        if (message instanceof Request) {
            Request request = (Request) message;
            DefaultFuture.sent(channel, request);   // 实现就是记录当前时间戳
        }
        if (exception != null) {
            if (exception instanceof RuntimeException) {
                throw (RuntimeException) exception;
            } else if (exception instanceof RemotingException) {
                throw (RemotingException) exception;
            } else {
                throw new RemotingException(channel.getLocalAddress(), channel.getRemoteAddress(),
                        exception.getMessage(), exception);
            }
        }
    }

    @Override
    // 如果 message 是 response，此方法主要是从 DefaultFuture 的 FUTURES 中获取 response id 对应的 DefaultFuture，然后将 response 赋值为 DefaultFuture 的 response 属性
    public void received(Channel channel, Object message) throws RemotingException {
        channel.setAttribute(KEY_READ_TIMESTAMP, System.currentTimeMillis());   // 设置 READ_TIMESTAMP 属性
        final ExchangeChannel exchangeChannel = HeaderExchangeChannel.getOrAddChannel(channel); // 尝试获取 channel 中的 HeaderExchangeChannel 属性值，没有的话就新建一个，然后保存到 channel 中
        try {
            if (message instanceof Request) {   // 处理 request 消息
                // handle request.
                Request request = (Request) message;
                if (request.isEvent()) {    // 如果是事件类型的消息
                    handlerEvent(channel, request);
                } else {
                    if (request.isTwoWay()) {
                        handleRequest(exchangeChannel, request);    // 调用服务方法，将返回结果通过 channel 写回
                    } else {
                        handler.received(exchangeChannel, request.getData());
                    }
                }
            } else if (message instanceof Response) {   // 此方法主要是从 DefaultFuture 的 FUTURES 中获取 response id 对应的 DefaultFuture，然后将 response 赋值为 DefaultFuture 的 response 属性
                handleResponse(channel, (Response) message);
            } else if (message instanceof String) { // 处理字符串
                if (isClientSide(channel)) {    // 客户端处理方式
                    Exception e = new Exception("Dubbo client can not supported string message: " + message + " in channel: " + channel + ", url: " + channel.getUrl());
                    logger.error(e.getMessage(), e);
                } else {    // 服务端处理方式，当做 telnet 来处理
                    String echo = handler.telnet(channel, (String) message);
                    if (echo != null && echo.length() > 0) {
                        channel.send(echo);
                    }
                }
            } else {
                handler.received(exchangeChannel, message);
            }
        } finally {
            HeaderExchangeChannel.removeChannelIfDisconnected(channel);
        }
    }

    @Override
    public void caught(Channel channel, Throwable exception) throws RemotingException {
        if (exception instanceof ExecutionException) {
            ExecutionException e = (ExecutionException) exception;
            Object msg = e.getRequest();    // 捕获 ExecutionException 的 Request 信息
            if (msg instanceof Request) {
                Request req = (Request) msg;
                if (req.isTwoWay() && !req.isHeartbeat()) { // req 为 twoWay 且非心跳消息
                    Response res = new Response(req.getId(), req.getVersion());
                    res.setStatus(Response.SERVER_ERROR);
                    res.setErrorMessage(StringUtils.toString(e));
                    channel.send(res);  // 这里应该是将执行异常返回给请求者
                    return;
                }
            }
        }
        ExchangeChannel exchangeChannel = HeaderExchangeChannel.getOrAddChannel(channel);
        try {
            handler.caught(exchangeChannel, exception);
        } finally {
            HeaderExchangeChannel.removeChannelIfDisconnected(channel);
        }
    }

    @Override
    public ChannelHandler getHandler() {
        if (handler instanceof ChannelHandlerDelegate) {
            return ((ChannelHandlerDelegate) handler).getHandler();
        } else {
            return handler;
        }
    }
}
