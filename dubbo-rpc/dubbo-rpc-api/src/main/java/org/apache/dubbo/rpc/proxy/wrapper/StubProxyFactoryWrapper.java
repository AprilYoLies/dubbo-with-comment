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
package org.apache.dubbo.rpc.proxy.wrapper;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.bytecode.Wrapper;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.service.GenericService;

import java.lang.reflect.Constructor;

import static org.apache.dubbo.common.constants.RpcConstants.LOCAL_KEY;
import static org.apache.dubbo.common.constants.RpcConstants.STUB_KEY;
import static org.apache.dubbo.common.constants.RpcConstants.IS_SERVER_KEY;
import static org.apache.dubbo.common.constants.RpcConstants.STUB_EVENT_KEY;
import static org.apache.dubbo.common.constants.RpcConstants.DEFAULT_STUB_EVENT;
import static org.apache.dubbo.common.constants.RpcConstants.STUB_EVENT_METHODS_KEY;

/**
 * StubProxyFactoryWrapper
 */
public class StubProxyFactoryWrapper implements ProxyFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(StubProxyFactoryWrapper.class);

    // 实际为 JavassistProxyFactory，因为 StubProxyFactoryWrapper 是一个 wrapper 类，所以对 ProxyFactory 实例进行了封装
    private final ProxyFactory proxyFactory;

    private Protocol protocol;

    public StubProxyFactoryWrapper(ProxyFactory proxyFactory) {
        this.proxyFactory = proxyFactory;
    }

    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    @Override
    public <T> T getProxy(Invoker<T> invoker, boolean generic) throws RpcException {
        return proxyFactory.getProxy(invoker, generic);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T> T getProxy(Invoker<T> invoker) throws RpcException {
        // public class org.apache.dubbo.common.bytecode.Proxy0 extends org.apache.dubbo.common.bytecode.Proxy {
        //     public Object newInstance(java.lang.reflect.InvocationHandler h) {
        //         return new org.apache.dubbo.common.bytecode.proxy0($1);
        //     }
        // }
        // proxy 实例的源代码
        // public class org.apache.dubbo.common.bytecode.proxy0 implements com.alibaba.dubbo.rpc.service.EchoService, org.apache.dubbo.demo.DemoService {
        //     public static java.lang.reflect.Method[] methods;
        //     private java.lang.reflect.InvocationHandler handler;
        //     public <init>(
        //     java.lang.reflect.InvocationHandler arg0)
        //
        //     {
        //         handler = $1;
        //     }
        //
        //     public java.lang.String sayHello(java.lang.String arg0) {
        //         Object[] args = new Object[1];
        //         args[0] = ($w) $1;
        //         Object ret = handler.invoke(this, methods[0], args);
        //         return (java.lang.String) ret;
        //     }
        //
        //     public java.lang.Object $echo(java.lang.Object arg0) {
        //         Object[] args = new Object[1];
        //         args[0] = ($w) $1;
        //         Object ret = handler.invoke(this, methods[1], args);
        //         return (java.lang.Object) ret;
        //     }
        // }
        T proxy = proxyFactory.getProxy(invoker);   // 实际是 JavassistProxyFactory，invoker 的是 MockClusterInvoker，返回的是 proxy0
        if (GenericService.class != invoker.getInterface()) {
            URL url = invoker.getUrl(); // 这是原始的 url
            String stub = url.getParameter(STUB_KEY, url.getParameter(LOCAL_KEY));  // stub 默认为 url 的 local 属性
            if (ConfigUtils.isNotEmpty(stub)) {
                Class<?> serviceType = invoker.getInterface();
                if (ConfigUtils.isDefault(stub)) {  // stub 为 true 或者 default
                    if (url.hasParameter(STUB_KEY)) {
                        stub = serviceType.getName() + "Stub";  // org.apache.dubbo.demo.DemoServiceStub
                    } else {
                        stub = serviceType.getName() + "Local"; // org.apache.dubbo.demo.DemoServiceLocal
                    }
                }
                try {
                    Class<?> stubClass = ReflectUtils.forName(stub);
                    if (!serviceType.isAssignableFrom(stubClass)) { // Stub 类必须为服务类或者其子类
                        throw new IllegalStateException("The stub implementation class " + stubClass.getName() + " not implement interface " + serviceType.getName());
                    }
                    try {
                        Constructor<?> constructor = ReflectUtils.findConstructor(stubClass, serviceType);
                        proxy = (T) constructor.newInstance(new Object[]{proxy});   // Stub 类有服务类为参数的构造函数，那么就用这个 Stub 类包装 proxy 类
                        //export stub service
                        URLBuilder urlBuilder = URLBuilder.from(url);
                        if (url.getParameter(STUB_EVENT_KEY, DEFAULT_STUB_EVENT)) { // dubbo.stub.event 默认为 false
                            urlBuilder.addParameter(STUB_EVENT_METHODS_KEY, StringUtils.join(Wrapper.getWrapper(proxy.getClass()).getDeclaredMethodNames(), ","));  // dubbo.stub.event.methods
                            urlBuilder.addParameter(IS_SERVER_KEY, Boolean.FALSE.toString()); // isServer -> false
                            try {
                                export(proxy, (Class) invoker.getInterface(), urlBuilder.build());
                            } catch (Exception e) {
                                LOGGER.error("export a stub service error.", e);
                            }
                        }
                    } catch (NoSuchMethodException e) {
                        throw new IllegalStateException("No such constructor \"public " + stubClass.getSimpleName() + "(" + serviceType.getName() + ")\" in stub implementation class " + stubClass.getName(), e);
                    }
                } catch (Throwable t) {
                    LOGGER.error("Failed to create stub implementation class " + stub + " in consumer " + NetUtils.getLocalHost() + " use dubbo version " + Version.getVersion() + ", cause: " + t.getMessage(), t);
                    // ignore
                }
            }
        }
        return proxy;
    }

    @Override
    public <T> Invoker<T> getInvoker(T proxy, Class<T> type, URL url) throws RpcException {
        // 调用 JavassistProxyFactory 的 getInvoker 方法
        return proxyFactory.getInvoker(proxy, type, url);
    }

    private <T> Exporter<T> export(T instance, Class<T> type, URL url) {
        return protocol.export(proxyFactory.getInvoker(instance, type, url));
    }
}
