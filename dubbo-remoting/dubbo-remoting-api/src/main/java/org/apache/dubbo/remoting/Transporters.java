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
package org.apache.dubbo.remoting;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.remoting.transport.ChannelHandlerAdapter;
import org.apache.dubbo.remoting.transport.ChannelHandlerDispatcher;

/**
 * Transporter facade. (API, Static, ThreadSafe)
 */
public class Transporters {

    static {
        // check duplicate jar package
        Version.checkDuplicate(Transporters.class);
        Version.checkDuplicate(RemotingException.class);
    }

    private Transporters() {
    }

    public static Server bind(String url, ChannelHandler... handler) throws RemotingException {
        return bind(URL.valueOf(url), handler);
    }

    // handlers 这里实际是一个 DecodeHandler
    public static Server bind(URL url, ChannelHandler... handlers) throws RemotingException {
        // 验证参数的有效性
        if (url == null) {
            throw new IllegalArgumentException("url == null");
        }
        if (handlers == null || handlers.length == 0) {
            throw new IllegalArgumentException("handlers == null");
        }
        ChannelHandler handler;
        if (handlers.length == 1) {
            // 如果 handlers 只要一个，那么就直接使用
            handler = handlers[0];
        } else {
            // 如果 handlers 有多个，那么将其封装为 ChannelHandlerDispatcher 再使用
            handler = new ChannelHandlerDispatcher(handlers);
        }
        // getTransporter 方法获取的 Transpoter 的实例代码
        // package org.apache.dubbo.remoting;
        // import org.apache.dubbo.common.extension.ExtensionLoader;
        // public class Transporter$Adaptive implements org.apache.dubbo.remoting.Transporter {
        //     public org.apache.dubbo.remoting.Client connect(org.apache.dubbo.common.URL arg0, org.apache.dubbo.remoting.ChannelHandler arg1) throws org.apache.dubbo.remoting.RemotingException {
        //         if (arg0 == null) throw new IllegalArgumentException("url == null");
        //         org.apache.dubbo.common.URL url = arg0;
        //         String extName = url.getParameter("client", url.getParameter("transporter", "netty"));
        //         if (extName == null)
        //             throw new IllegalStateException("Failed to get extension (org.apache.dubbo.remoting.Transporter) name from url (" + url.toString() + ") use keys([client, transporter])");
        //         org.apache.dubbo.remoting.Transporter extension = (org.apache.dubbo.remoting.Transporter) ExtensionLoader.getExtensionLoader(org.apache.dubbo.remoting.Transporter.class).getExtension(extName);
        //         return extension.connect(arg0, arg1);
        //     }
        //
        //     public org.apache.dubbo.remoting.Server bind(org.apache.dubbo.common.URL arg0, org.apache.dubbo.remoting.ChannelHandler arg1) throws org.apache.dubbo.remoting.RemotingException {
        //         if (arg0 == null) throw new IllegalArgumentException("url == null");
        //         org.apache.dubbo.common.URL url = arg0;
        // 可以看到这里是最先匹配 server 参数，如果没有的话，再匹配 transporter 参数，如果还是没有，则默认使用 netty 参数
        //         String extName = url.getParameter("server", url.getParameter("transporter", "netty"));
        //         if (extName == null)
        //             throw new IllegalStateException("Failed to get extension (org.apache.dubbo.remoting.Transporter) name from url (" + url.toString() + ") use keys([server, transporter])");
        // 这里获取的 extension 其实是一个 NettyTransporter
        //         org.apache.dubbo.remoting.Transporter extension = (org.apache.dubbo.remoting.Transporter) ExtensionLoader.getExtensionLoader(org.apache.dubbo.remoting.Transporter.class).getExtension(extName);
        //         return extension.bind(arg0, arg1);
        //     }
        // }
        return getTransporter().bind(url, handler);
    }

    public static Client connect(String url, ChannelHandler... handler) throws RemotingException {
        return connect(URL.valueOf(url), handler);
    }

    public static Client connect(URL url, ChannelHandler... handlers) throws RemotingException {
        if (url == null) {
            throw new IllegalArgumentException("url == null");
        }
        ChannelHandler handler;
        if (handlers == null || handlers.length == 0) {
            handler = new ChannelHandlerAdapter();  // 针对没有 handler 的情况
        } else if (handlers.length == 1) {
            handler = handlers[0];  // 针对只有一个 handler 的情况
        } else {
            handler = new ChannelHandlerDispatcher(handlers);   // 这里是针对多个 handler 的情况
        }
        return getTransporter().connect(url, handler);
    }

    public static Transporter getTransporter() {
        // getTransporter 方法获取的 Transpoter 的实例代码
        // package org.apache.dubbo.remoting;
        // import org.apache.dubbo.common.extension.ExtensionLoader;
        // public class Transporter$Adaptive implements org.apache.dubbo.remoting.Transporter {
        // 这一部分代码针对服务的消费端
        //     public org.apache.dubbo.remoting.Client connect(org.apache.dubbo.common.URL arg0, org.apache.dubbo.remoting.ChannelHandler arg1) throws org.apache.dubbo.remoting.RemotingException {
        //         if (arg0 == null) throw new IllegalArgumentException("url == null");
        //         org.apache.dubbo.common.URL url = arg0;
        //         String extName = url.getParameter("client", url.getParameter("transporter", "netty"));
        //         if (extName == null)
        //             throw new IllegalStateException("Failed to get extension (org.apache.dubbo.remoting.Transporter) name from url (" + url.toString() + ") use keys([client, transporter])");
        // 此处获取的 extension 为 NettyTransporter
        //         org.apache.dubbo.remoting.Transporter extension = (org.apache.dubbo.remoting.Transporter) ExtensionLoader.getExtensionLoader(org.apache.dubbo.remoting.Transporter.class).getExtension(extName);
        //         return extension.connect(arg0, arg1);
        //     }
        //
        // 这一部分代码针对服务的发布端
        //     public org.apache.dubbo.remoting.Server bind(org.apache.dubbo.common.URL arg0, org.apache.dubbo.remoting.ChannelHandler arg1) throws org.apache.dubbo.remoting.RemotingException {
        //         if (arg0 == null) throw new IllegalArgumentException("url == null");
        //         org.apache.dubbo.common.URL url = arg0;
        // 可以看到这里是最先匹配 server 参数，如果没有的话，再匹配 transporter 参数，如果还是没有，则默认使用 netty 参数
        //         String extName = url.getParameter("server", url.getParameter("transporter", "netty"));
        //         if (extName == null)
        //             throw new IllegalStateException("Failed to get extension (org.apache.dubbo.remoting.Transporter) name from url (" + url.toString() + ") use keys([server, transporter])");
        // 这里获取的 extension 其实是一个 NettyTransporter
        //         org.apache.dubbo.remoting.Transporter extension = (org.apache.dubbo.remoting.Transporter) ExtensionLoader.getExtensionLoader(org.apache.dubbo.remoting.Transporter.class).getExtension(extName);
        //         return extension.bind(arg0, arg1);
        //     }
        // }
        return ExtensionLoader.getExtensionLoader(Transporter.class).getAdaptiveExtension();
    }

}