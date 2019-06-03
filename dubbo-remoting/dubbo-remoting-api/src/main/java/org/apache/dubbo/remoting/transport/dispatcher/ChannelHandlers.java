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
package org.apache.dubbo.remoting.transport.dispatcher;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Dispatcher;
import org.apache.dubbo.remoting.exchange.support.header.HeartbeatHandler;
import org.apache.dubbo.remoting.transport.DecodeHandler;
import org.apache.dubbo.remoting.transport.MultiMessageHandler;

public class ChannelHandlers {

    private static ChannelHandlers INSTANCE = new ChannelHandlers();

    protected ChannelHandlers() {
    }

    // handler 实际是一个 DecodeHandler，此方法就是将 handler 层层包装成为 MultiMessageHandler
    public static ChannelHandler wrap(ChannelHandler handler, URL url) {
        // 通过 AllDispatcher 根据本函数的 handler 参数构建 AllChannelHandler，并最终包装成 MultiMessageHandler
        return ChannelHandlers.getInstance().wrapInternal(handler, url);
    }

    protected static ChannelHandlers getInstance() {
        return INSTANCE;
    }

    static void setTestingChannelHandlers(ChannelHandlers instance) {
        INSTANCE = instance;
    }

    // handler 实际是一个 DecodeHandler，dispatch 方法返回值是通过 AllDispatcher 根据本函数的 handler 参数构建出来的 AllChannelHandler，
    // 然后又通过 HeartbeatHandler 对 AllChannelHandler 进行了封装，最后又通过 MultiMessageHandler 对 HeartbeatHandler 进行封装，所以
    // 函数的最终返回值是一个 MultiMessageHandler 类型
    protected ChannelHandler wrapInternal(ChannelHandler handler, URL url) {    // MultiMessageHandler -> HeartbeatHandler -> AllChannelHandler
        return new MultiMessageHandler(new HeartbeatHandler(ExtensionLoader.getExtensionLoader(Dispatcher.class)
                // getAdaptiveExtension 返回的 extension 实例的源代码
                // package org.apache.dubbo.remoting;
                // import org.apache.dubbo.common.extension.ExtensionLoader;
                // public class Dispatcher$Adaptive implements org.apache.dubbo.remoting.Dispatcher {
                //     public org.apache.dubbo.remoting.ChannelHandler dispatch(org.apache.dubbo.remoting.ChannelHandler arg0, org.apache.dubbo.common.URL arg1) {
                //         if (arg1 == null) throw new IllegalArgumentException("url == null");
                //         org.apache.dubbo.common.URL url = arg1;
                // extName 由 url 中的 dispather 参数所决定，如果没有获取到的话，就使用 channel.handler 参数值代替，还是为空的话，那就直接使用 all
                //         String extName = url.getParameter("dispatcher", url.getParameter("dispather", url.getParameter("channel.handler", "all")));
                //         if (extName == null)
                //             throw new IllegalStateException("Failed to get extension (org.apache.dubbo.remoting.Dispatcher) name from url (" + url.toString() + ") use keys([dispatcher, dispather, channel.handler])");
                // 此处获取的 extension 为 org.apache.dubbo.remoting.transport.dispatcher.all.AllDispatcher
                //         org.apache.dubbo.remoting.Dispatcher extension = (org.apache.dubbo.remoting.Dispatcher) ExtensionLoader.getExtensionLoader(org.apache.dubbo.remoting.Dispatcher.class).getExtension(extName);
                //         return extension.dispatch(arg0, arg1);
                //     }
                // }
                .getAdaptiveExtension().dispatch(handler, url)));
    }
}
