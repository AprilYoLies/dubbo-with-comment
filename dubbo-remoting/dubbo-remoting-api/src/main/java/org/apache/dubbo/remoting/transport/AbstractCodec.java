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
package org.apache.dubbo.remoting.transport;

import java.io.IOException;
import java.net.InetSocketAddress;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.constants.RemotingConstants;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.serialize.Serialization;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.Codec2;

import static org.apache.dubbo.common.constants.CommonConstants.SIDE_KEY;

/**
 * AbstractCodec
 */
public abstract class AbstractCodec implements Codec2 {

    private static final Logger logger = LoggerFactory.getLogger(AbstractCodec.class);

    private static final String CLIENT_SIDE = "client";

    private static final String SERVER_SIDE = "server";

    protected static void checkPayload(Channel channel, long size) throws IOException {
        // 默认负载为 8MB
        int payload = RemotingConstants.DEFAULT_PAYLOAD;
        if (channel != null && channel.getUrl() != null) {
            // 尝试从 url 中获取 payload 信息，如果没有相应的参数就使用默认的 8MB
            payload = channel.getUrl().getParameter(RemotingConstants.PAYLOAD_KEY, RemotingConstants.DEFAULT_PAYLOAD);
        }
        if (payload > 0 && size > payload) {
            // 传输的数据量已经是超过负载上限了
            ExceedPayloadLimitException e = new ExceedPayloadLimitException(
                    "Data length too large: " + size + ", max payload: " + payload + ", channel: " + channel);
            logger.error(e);
            throw e;
        }
    }

    protected Serialization getSerialization(Channel channel) {
        // getUrl 就是获取 NettyChannel 中的 url
        return CodecSupport.getSerialization(channel.getUrl());
    }

    protected boolean isClientSide(Channel channel) {
        String side = (String) channel.getAttribute(SIDE_KEY);
        if (CLIENT_SIDE.equals(side)) {
            // channel 的 side 属性为 client
            return true;
        } else if (SERVER_SIDE.equals(side)) {
            // channel 的 side 属性为 server，返回 false
            return false;
        } else {
            InetSocketAddress address = channel.getRemoteAddress();
            URL url = channel.getUrl();
            // 根据 url 和 address 判断 side 信息，然后缓存到 channel 中
            boolean isClient = url.getPort() == address.getPort()
                    && NetUtils.filterLocalHost(url.getIp()).equals(
                    NetUtils.filterLocalHost(address.getAddress()
                            .getHostAddress()));
            channel.setAttribute(SIDE_KEY, isClient ? CLIENT_SIDE
                    : SERVER_SIDE);
            return isClient;
        }
    }

    protected boolean isServerSide(Channel channel) {
        return !isClientSide(channel);
    }

}
