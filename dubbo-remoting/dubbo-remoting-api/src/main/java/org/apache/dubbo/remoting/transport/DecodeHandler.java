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

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Decodeable;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.exchange.Response;

public class DecodeHandler extends AbstractChannelHandlerDelegate {

    private static final Logger log = LoggerFactory.getLogger(DecodeHandler.class);

    public DecodeHandler(ChannelHandler handler) {
        super(handler);
    }

    @Override   // 之所以被称为 DecodeHandler，就是因为它会对 message 进行检查，确保消息被解码过吧
    public void received(Channel channel, Object message) throws RemotingException {    // 收到的消息有三种情况，分别进行处理
        if (message instanceof Decodeable) {
            decode(message);
        }

        if (message instanceof Request) {
            decode(((Request) message).getData());  // 对 request 中的 data 进行解码（只是尝试，因为 data 可能是已解码状态）
        }

        if (message instanceof Response) {
            decode(((Response) message).getResult());   // 对 response 中的 data 进行解码（只是尝试，因为 data 可能是已解码状态）
        }

        handler.received(channel, message);
    }

    private void decode(Object message) {
        if (message instanceof Decodeable) {
            try {
                ((Decodeable) message).decode();    // 尝试对 message 进行解码
                if (log.isDebugEnabled()) {
                    log.debug("Decode decodeable message " + message.getClass().getName());
                }
            } catch (Throwable e) {
                if (log.isWarnEnabled()) {
                    log.warn("Call Decodeable.decode failed: " + e.getMessage(), e);
                }
            } // ~ end of catch
        } // ~ end of if
    } // ~ end of method decode

}
