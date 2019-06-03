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
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.rpc.AsyncRpcResult;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;

import java.util.List;

import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_PROTOCOL;

import static org.apache.dubbo.common.constants.RpcConstants.REFERENCE_FILTER_KEY;
import static org.apache.dubbo.common.constants.RpcConstants.SERVICE_FILTER_KEY;

/**
 * ListenerProtocol
 */
public class ProtocolFilterWrapper implements Protocol {    // ProtocolFilterWrapper 会根据情况将 Invoker 包装成为 InvokerChain

    private final Protocol protocol;

    public ProtocolFilterWrapper(Protocol protocol) {
        if (protocol == null) {
            throw new IllegalArgumentException("protocol == null");
        }
        this.protocol = protocol;
    }

    /**
     * 构建 Invoker 链
     *
     * @param invoker 在 JavassistProxyFactory 类中创建的 AbstractProxyInvoker 匿名类
     * @param key     service.filter 或者 reference.filter
     * @param group   provider 或者 consumer
     * @param <T>
     * @return
     */
    private static <T> Invoker<T> buildInvokerChain(final Invoker<T> invoker, String key, String group) {
        Invoker<T> last = invoker;
        // getActivateExtension 方法是先获取 url 参数 key 的值中 default 元素（如果存在）之前的全部 extension，然后是不在 key 指定
        // 值中的其他 extension，最后才是剩下的 default（如果存在）元素之后的全部 extension
        List<Filter> filters = ExtensionLoader.getExtensionLoader(Filter.class).getActivateExtension(invoker.getUrl(), key, group);
        if (!filters.isEmpty()) {
            // 遍历所获取的全部 Filter extension 元素
            for (int i = filters.size() - 1; i >= 0; i--) {
                final Filter filter = filters.get(i);
                final Invoker<T> next = last;
                // 这里应该是使用 Invoker 对获取的 filter 进行了封装，last 指向新建的 Invoker
                last = new Invoker<T>() {

                    @Override
                    public Class<T> getInterface() {
                        // 使用 invoker 的 interface 作为新建 Invoker 的 getInterface 方法的返回值
                        return invoker.getInterface();
                    }

                    @Override
                    public URL getUrl() {
                        // 使用 invoker 的 url 作为新建 Invoker 的 getUrl 方法的返回值
                        return invoker.getUrl();
                    }

                    @Override
                    public boolean isAvailable() {
                        // 使用 invoker 的状态作为新建 Invoker 的 isAvailable 方法的返回值
                        return invoker.isAvailable();
                    }

                    @Override
                    public Result invoke(Invocation invocation) throws RpcException {
                        // 这个 filter 以 AccessLogFilter 的实现作为实例
                        // 执行了相应的 filter 的逻辑，再调用下一个 Invoker 的 invoke 方法
                        Result result = filter.invoke(next, invocation);
                        if (result instanceof AsyncRpcResult) {
                            // 这里是针对 AsyncRpcResult 类型 result 的处理方式
                            AsyncRpcResult asyncResult = (AsyncRpcResult) result;
                            // 将 asyncResult 交由 beforeContext、r -> filter.onResponse(r, invoker, invocation)、afterContext 进行处理
                            // 这里的 filter 以 org.apache.dubbo.rpc.filter.ContextFilter 为例
                            asyncResult.thenApplyWithContext(r -> filter.onResponse(r, invoker, invocation));
                            // 返回处理过的结果
                            return asyncResult;
                        } else {
                            // 这种方式，仅仅是少了 beforeContext、afterContext 两个函数的处理过程
                            return filter.onResponse(result, invoker, invocation);
                        }
                    }

                    @Override
                    public void destroy() {
                        // 使用 invoker 的 destroy 作为新建 Invoker 的 destroy 方法的返回值
                        invoker.destroy();
                    }

                    @Override
                    public String toString() {
                        // 使用 invoker 的 toString 作为新建 Invoker 的 toString 方法的返回值
                        return invoker.toString();
                    }
                };
            }
        }
        return last;
    }

    @Override
    public int getDefaultPort() {
        return protocol.getDefaultPort();
    }

    @Override
    // invoker 是在 JavassistProxyFactory 类中创建的 AbstractProxyInvoker 匿名类
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        if (REGISTRY_PROTOCOL.equals(invoker.getUrl().getProtocol())) {
            // 如果 url 使用的是 registry 协议，执行这个 export 方法
            return protocol.export(invoker);
        }
        // invoker 是在 JavassistProxyFactory 类中创建的 AbstractProxyInvoker 匿名类
        // buildInvokerChain 返回的是一个 Invoker，引用了 filter 和下一个 Invoker，调用 Invoker 的 invoke 方法，
        // 就会调用 filter 的 invoke 方法，这又会触发下一个 Invoker 的 invoke 方法，最后的一个 Invoker 即是
        // 在 JavassistProxyFactory 类中创建的 AbstractProxyInvoker 匿名类
        // 这个 protocol 就是真正的 Protocol 的实现类
        return protocol.export(buildInvokerChain(invoker, SERVICE_FILTER_KEY, CommonConstants.PROVIDER));
    }

    @Override
    public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
        if (REGISTRY_PROTOCOL.equals(url.getProtocol())) {
            // 得到的是 MockClusterInvoker
            return protocol.refer(type, url);
        }   // protocol.refer 主要是通过 getClients 获取到 List<ReferenceCountExchangeClient>，将其封装成为 DubboInvoker 再缓存到 invokers，最后将构建的 DubboInvoker 返回
        return buildInvokerChain(protocol.refer(type, url), REFERENCE_FILTER_KEY, CommonConstants.CONSUMER);
    }    // buildInvokerChain 是为 DubboInvoker 加了很多的 FilterInvoker 而构成 invoker 链，最后一个才是 DubboInvoker

    @Override
    public void destroy() {
        protocol.destroy();
    }

}
