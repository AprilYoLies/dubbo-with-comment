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
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.Transporters;
import org.apache.dubbo.remoting.exchange.ExchangeClient;
import org.apache.dubbo.remoting.exchange.ExchangeHandler;
import org.apache.dubbo.remoting.exchange.ExchangeServer;
import org.apache.dubbo.remoting.exchange.Exchanger;
import org.apache.dubbo.remoting.transport.DecodeHandler;

/**
 * DefaultMessenger
 */
public class HeaderExchanger implements Exchanger {

    public static final String NAME = "header";

    // 此方法针对服务的消费端 HeaderExchangeClient -> NettyClient
    @Override   // Transporters.connect 方法，构建 NettyClient，实际在这个构造函数中，完成了 Netty 通信相关的全部设置
    public ExchangeClient connect(URL url, ExchangeHandler handler) throws RemotingException {  // DecodeHandler -> HeaderExchangeHandler -> DubboProtocol$1
        return new HeaderExchangeClient(Transporters.connect(url, new DecodeHandler(new HeaderExchangeHandler(handler))), true);
    }

    @Override   // 此方法针对服务发布端
    // handler 为 org.apache.dubbo.rpc.protocol.dubbo.DubboProtocol.requestHandler，实际是一个 ExchangeHandlerAdapter 实例
    public ExchangeServer bind(URL url, ExchangeHandler handler) throws RemotingException {
        // 这里通过 HeaderExchangeHandler 对 ExchangeHandlerAdapter 进行了封装，然后又通过 DecodeHandler 对 HeaderExchangeHandler 进行了封装
        return new HeaderExchangeServer(Transporters.bind(url, new DecodeHandler(new HeaderExchangeHandler(handler))));
    }

}
