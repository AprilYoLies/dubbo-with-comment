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

import org.apache.dubbo.common.constants.RemotingConstants;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.exchange.Response;
import org.apache.dubbo.remoting.transport.AbstractChannelHandlerDelegate;

public class HeartbeatHandler extends AbstractChannelHandlerDelegate {

    private static final Logger logger = LoggerFactory.getLogger(HeartbeatHandler.class);

    public static final String KEY_READ_TIMESTAMP = "READ_TIMESTAMP";

    public static final String KEY_WRITE_TIMESTAMP = "WRITE_TIMESTAMP";

    public HeartbeatHandler(ChannelHandler handler) {
        super(handler);
    }

    @Override
    public void connected(Channel channel) throws RemotingException {
        // 为 channel 添加一个 READ_TIMESTAMP 属性，值为当前的时间戳
        setReadTimestamp(channel);
        // 为 channel 添加一个 WRITE_TIMESTAMP 属性，值为当前的时间戳
        setWriteTimestamp(channel);
        // handler 为 AllChannelHandler 的实例
        handler.connected(channel);
    }

    @Override
    public void disconnected(Channel channel) throws RemotingException {
        clearReadTimestamp(channel);
        clearWriteTimestamp(channel);
        handler.disconnected(channel);
    }

    @Override
    public void sent(Channel channel, Object message) throws RemotingException {
        setWriteTimestamp(channel);
        handler.sent(channel, message);
    }

    @Override   // 之所以被称为 HeartbeatHandler，大概是因为它分别对 HeartBeat 的 Request 和 Response 进行了相应的处理吧
    public void received(Channel channel, Object message) throws RemotingException {
        setReadTimestamp(channel);  // 为 channel 添加一个 READ_TIMESTAMP 属性，值为当前的时间戳
        if (isHeartbeatRequest(message)) {  // 这里针对 HeartBeatRequest 进行处理
            Request req = (Request) message;
            if (req.isTwoWay()) {
                Response res = new Response(req.getId(), req.getVersion()); // 构建 HeartBeat 相应消息
                res.setEvent(Response.HEARTBEAT_EVENT); // 设置响应消息为 HeartBeat 事件
                channel.send(res);  // 收到的是心跳消息，直接发回一个心跳相应
                if (logger.isInfoEnabled()) {
                    int heartbeat = channel.getUrl().getParameter(RemotingConstants.HEARTBEAT_KEY, 0);
                    if (logger.isDebugEnabled()) {  // 日志记录当前心跳消息
                        logger.debug("Received heartbeat from remote channel " + channel.getRemoteAddress()
                                + ", cause: The channel has no data-transmission exceeds a heartbeat period"
                                + (heartbeat > 0 ? ": " + heartbeat + "ms" : ""));
                    }
                }
            }
            return;
        }
        if (isHeartbeatResponse(message)) {
            if (logger.isDebugEnabled()) {  // 这里负责处理 HeartBeat 相应消息
                logger.debug("Receive heartbeat response in thread " + Thread.currentThread().getName());
            }
            return;
        }
        handler.received(channel, message);
    }

    private void setReadTimestamp(Channel channel) {
        // 为 channel 添加一个 READ_TIMESTAMP 属性，值为当前的时间戳
        channel.setAttribute(KEY_READ_TIMESTAMP, System.currentTimeMillis());
    }

    private void setWriteTimestamp(Channel channel) {
        // 为 channel 添加一个 WRITE_TIMESTAMP 属性，值为当前的时间戳
        channel.setAttribute(KEY_WRITE_TIMESTAMP, System.currentTimeMillis());
    }

    private void clearReadTimestamp(Channel channel) {
        channel.removeAttribute(KEY_READ_TIMESTAMP);
    }

    private void clearWriteTimestamp(Channel channel) {
        channel.removeAttribute(KEY_WRITE_TIMESTAMP);
    }

    private boolean isHeartbeatRequest(Object message) {
        return message instanceof Request && ((Request) message).isHeartbeat();
    }

    private boolean isHeartbeatResponse(Object message) {
        return message instanceof Response && ((Response) message).isHeartbeat();
    }
}
