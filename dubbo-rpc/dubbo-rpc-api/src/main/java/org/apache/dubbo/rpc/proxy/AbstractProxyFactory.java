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

import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.service.GenericService;

import com.alibaba.dubbo.rpc.service.EchoService;

import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SPLIT_PATTERN;
import static org.apache.dubbo.common.constants.RpcConstants.INTERFACES;

/**
 * AbstractProxyFactory
 */
public abstract class AbstractProxyFactory implements ProxyFactory {

    @Override   // 此方法主要是为构造 proxy0 做准备，默认会为服务接口和 EchoService 创建方法的代理，但是如果 url 指定了 interfaces 参数，那么还需要额外对这些接口的方法进行代理
    public <T> T getProxy(Invoker<T> invoker) throws RpcException {
        return getProxy(invoker, false);
    }

    @Override   // 此方法主要是为构造 proxy0 做准备，默认会为服务接口和 EchoService 创建方法的代理，但是如果 url 指定了 interfaces 参数，那么还需要额外对这些接口的方法进行代理
    public <T> T getProxy(Invoker<T> invoker, boolean generic) throws RpcException {
        Class<?>[] interfaces = null;
        String config = invoker.getUrl().getParameter(INTERFACES);  // interfaces
        if (config != null && config.length() > 0) {
            String[] types = COMMA_SPLIT_PATTERN.split(config);
            if (types != null && types.length > 0) {
                interfaces = new Class<?>[types.length + 2];
                interfaces[0] = invoker.getInterface();  // 这里也就是说将 invoker 和 EchoService 的 class 保存到 interfaces
                interfaces[1] = EchoService.class;
                for (int i = 0; i < types.length; i++) {    // 将 config 所代表的 class 也添加到 interfaces 中去
                    // TODO can we load successfully for a different classloader?.
                    interfaces[i + 2] = ReflectUtils.forName(types[i]);
                }
            }   // 最终结果就是 invoker.getInterface、EchoService、invoker 中 url 对应的 interface 的切割
        }
        if (interfaces == null) {
            interfaces = new Class<?>[]{invoker.getInterface(), EchoService.class}; // 没有其他的，那就只填充这两个
        }

        if (!GenericService.class.isAssignableFrom(invoker.getInterface()) && generic) {    // 泛型的处理方式，这里不做深究
            int len = interfaces.length;    // 这种情况下，还补填一个 GenericService.class
            Class<?>[] temp = interfaces;
            interfaces = new Class<?>[len + 1];
            System.arraycopy(temp, 0, interfaces, 0, len);
            interfaces[len] = com.alibaba.dubbo.rpc.service.GenericService.class;
        }

        return getProxy(invoker, interfaces);
    }

    public abstract <T> T getProxy(Invoker<T> invoker, Class<?>[] types);

}
