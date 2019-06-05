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
package org.apache.dubbo.rpc.protocol;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.ExporterListener;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.InvokerListener;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.listener.ListenerExporterWrapper;
import org.apache.dubbo.rpc.listener.ListenerInvokerWrapper;

import java.util.Collections;

import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_PROTOCOL;

import static org.apache.dubbo.common.constants.RpcConstants.INVOKER_LISTENER_KEY;
import static org.apache.dubbo.common.constants.RpcConstants.EXPORTER_LISTENER_KEY;

/**
 * ListenerProtocol
 */
public class ProtocolListenerWrapper implements Protocol {  // 之所以被称为 ProtocolListenerWrapper，大概是因为它会根据协议将结果包装成为 ListenerInvokerWrapper 吧
    // 被包装的 protocol，类型为 ProtocolFilterWrapper
    private final Protocol protocol;

    public ProtocolListenerWrapper(Protocol protocol) {
        if (protocol == null) {
            throw new IllegalArgumentException("protocol == null");
        }
        this.protocol = protocol;
    }

    @Override
    // 从 protocol 属性中获取默认端口
    public int getDefaultPort() {
        return protocol.getDefaultPort();
    }

    @Override
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        if (REGISTRY_PROTOCOL.equals(invoker.getUrl().getProtocol())) {
            // 如果 url 使用的协议是 registry，就会执行此 export 方法
            return protocol.export(invoker);    // 本方法主要是根据 originInvoker 构建了 exporter，在 zookeeper 中创建了对应的路径，并添加了监听器以检测参数的变化，同步更新配置信息并重新 export
        }   // protocol.export 方法将 invoker 封装为 DubboExporter 后缓存到 exporterMap，根据 url 创建了 HeaderExchangeServer（封装了 NettyServer），然后将此 HeaderExchangeServer 进行缓存，返回构建的 DubboExporter
        // ListenerExporterWrapper 对直接返回的 Exporter 进行了封装，主要是增加了一些监听器属性
        return new ListenerExporterWrapper<T>(protocol.export(invoker),
                Collections.unmodifiableList(ExtensionLoader.getExtensionLoader(ExporterListener.class)
                        // getActivateExtension 这个函数主要是根据 url 中的 exporter.listener 参数，条件性的获取
                        // org.apache.dubbo.rpc.ExporterListener 接口对应的 extension
                        .getActivateExtension(invoker.getUrl(), EXPORTER_LISTENER_KEY)));
    }

    @Override
    public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
        if (REGISTRY_PROTOCOL.equals(url.getProtocol())) {  // 如果 url 是 registry 协议，直接向下传递
            // 构建 RegistryDirectory，为其填充 registry 和 protocol 属性，通过此 registry 在 zookeeper 中构建 consumer 路径，然后由 registry 通过 consumer url 得到
            // 对应的 invoker，填充到 RegistryDirectory 中，最后通过 cluster 参数对 RegistryDirectory 进行一定的封装，缓存封装结果并返回
            return protocol.refer(type, url);
        }
        return new ListenerInvokerWrapper<T>(protocol.refer(type, url), // 否则会将 refer 结果包装成为 ListenerInvokerWrapper
                Collections.unmodifiableList(
                        ExtensionLoader.getExtensionLoader(InvokerListener.class)
                                .getActivateExtension(url, INVOKER_LISTENER_KEY)));
    }

    @Override
    public void destroy() {
        protocol.destroy();
    }

}
