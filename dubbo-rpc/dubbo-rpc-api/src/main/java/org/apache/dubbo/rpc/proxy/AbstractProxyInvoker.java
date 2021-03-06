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
package org.apache.dubbo.rpc.proxy;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.rpc.AsyncContextImpl;
import org.apache.dubbo.rpc.AsyncRpcResult;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcResult;
import org.apache.dubbo.rpc.support.RpcUtils;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CompletableFuture;

/**
 * InvokerWrapper
 */
public abstract class AbstractProxyInvoker<T> implements Invoker<T> {
    Logger logger = LoggerFactory.getLogger(AbstractProxyInvoker.class);

    private final T proxy;

    private final Class<T> type;

    private final URL url;

    // 以 DemoServiceImpl 为例，这里的 proxy 为 DemoServiceImpl，type 为 org.apache.dubbo.demo.DemoService
    // url 为 injvm://127.0.0.1/org.apache.dubbo.demo.DemoService?
    //         anyhost=true&
    //         application=demo-provider&
    //         bean.name=org.apache.dubbo.demo.DemoService&
    //         bind.ip=192.168.1.104&
    //         bind.port=20880&
    //         deprecated=false&
    //         dubbo=2.0.2&
    //         dynamic=true&
    //         generic=false&
    //         interface=org.apache.dubbo.demo.DemoService&
    //         methods=sayHello&
    //         pid=5188&register=true&
    //         release=&side=provider&
    //         timestamp=1558341157391
    public AbstractProxyInvoker(T proxy, Class<T> type, URL url) {
        if (proxy == null) {
            throw new IllegalArgumentException("proxy == null");
        }
        if (type == null) {
            throw new IllegalArgumentException("interface == null");
        }
        if (!type.isInstance(proxy)) {
            throw new IllegalArgumentException(proxy.getClass().getName() + " not implement interface " + type);
        }
        this.proxy = proxy;
        this.type = type;
        this.url = url;
    }

    @Override
    public Class<T> getInterface() {
        return type;
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public void destroy() {
    }

    // TODO Unified to AsyncResult?
    @Override
    public Result invoke(Invocation invocation) throws RpcException {
        RpcContext rpcContext = RpcContext.getContext();
        try {   // 此时 proxy 为 DemoServiceImpl，methodName 为 sayHello
            Object obj = doInvoke(proxy, invocation.getMethodName(), invocation.getParameterTypes(), invocation.getArguments());
            if (RpcUtils.isReturnTypeFuture(invocation)) {  // 如果 invocation 的 future_returntype 指定为 true
                return new AsyncRpcResult((CompletableFuture<Object>) obj); // 将结果包装成为 AsyncRpcResult
            } else if (rpcContext.isAsyncStarted()) { // ignore obj in case of RpcContext.startAsync()? always rely on user to write back.
                return new AsyncRpcResult(((AsyncContextImpl) (rpcContext.getAsyncContext())).getInternalFuture());
            } else {
                return new RpcResult(obj);  // 就是正常的 sync 结果，直接将其封装成为 RpcResult
            }
        } catch (InvocationTargetException e) {
            // TODO async throw exception before async thread write back, should stop asyncContext
            if (rpcContext.isAsyncStarted() && !rpcContext.stopAsync()) {
                logger.error("Provider async started, but got an exception from the original method, cannot write the exception back to consumer because an async result may have returned the new thread.", e);
            }
            return new RpcResult(e.getTargetException());
        } catch (Throwable e) {
            throw new RpcException("Failed to invoke remote proxy method " + invocation.getMethodName() + " to " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    protected abstract Object doInvoke(T proxy, String methodName, Class<?>[] parameterTypes, Object[] arguments) throws Throwable;

    @Override
    public String toString() {
        return getInterface() + " -> " + (getUrl() == null ? " " : getUrl().toString());
    }


}
