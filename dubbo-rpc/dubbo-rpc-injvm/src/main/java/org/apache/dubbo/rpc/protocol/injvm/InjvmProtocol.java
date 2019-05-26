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
package org.apache.dubbo.rpc.protocol.injvm;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.protocol.AbstractProtocol;
import org.apache.dubbo.rpc.support.ProtocolUtils;

import java.util.Map;

import static org.apache.dubbo.common.constants.ConfigConstants.SCOPE_KEY;
import static org.apache.dubbo.common.constants.ConfigConstants.SCOPE_LOCAL;
import static org.apache.dubbo.common.constants.ConfigConstants.SCOPE_REMOTE;
import static org.apache.dubbo.common.constants.RpcConstants.GENERIC_KEY;
import static org.apache.dubbo.common.constants.RpcConstants.LOCAL_PROTOCOL;

/**
 * InjvmProtocol
 */
public class InjvmProtocol extends AbstractProtocol implements Protocol {

    public static final String NAME = LOCAL_PROTOCOL;

    public static final int DEFAULT_PORT = 0;
    private static InjvmProtocol INSTANCE;

    public InjvmProtocol() {
        INSTANCE = this;
    }

    public static InjvmProtocol getInjvmProtocol() {
        if (INSTANCE == null) { // 加载的过程会调用对应 class 的 newInstance 方法，就会对 INSTANCE 进行初始化
            ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(InjvmProtocol.NAME); // load
        }
        return INSTANCE;
    }

    static Exporter<?> getExporter(Map<String, Exporter<?>> map, URL key) {
        Exporter<?> result = null;

        if (!key.getServiceKey().contains("*")) {   // 如果 url 的 interface 属性有 *
            result = map.get(key.getServiceKey());  // 直接从 map 中获取 interface 属性值对应的属性
        } else {
            if (CollectionUtils.isNotEmptyMap(map)) {
                for (Exporter<?> exporter : map.values()) {
                    if (UrlUtils.isServiceKeyMatch(key, exporter.getInvoker().getUrl())) {  // 尝试从 map 中获取 key 对应的 export
                        result = exporter;
                        break;
                    }
                }
            }
        }

        if (result == null) {
            return null;
        } else if (ProtocolUtils.isGeneric( // invoker 的 url 的 generic 属性不能为 true 或 nativejava 或 bean 或 probobuf-json
                result.getInvoker().getUrl().getParameter(GENERIC_KEY))) {
            return null;
        } else {
            return result;
        }
    }

    @Override
    public int getDefaultPort() {
        return DEFAULT_PORT;
    }

    @Override
    // 这里的 invoker 实际是一个 AbstractProxyInvoker，是通过 new 的方式获得，它引用了 wrapper（动态构造）实例，保存了 DemoServiceImpl、DemoService、url 地址
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        return new InjvmExporter<T>(invoker, invoker.getUrl().getServiceKey(), exporterMap);
    }

    @Override
    public <T> Invoker<T> refer(Class<T> serviceType, URL url) throws RpcException {
        return new InjvmInvoker<T>(serviceType, url, url.getServiceKey(), exporterMap);
    }

    public boolean isInjvmRefer(URL url) {
        String scope = url.getParameter(SCOPE_KEY); // scope
        // Since injvm protocol is configured explicitly, we don't need to set any extra flag, use normal refer process.
        if (SCOPE_LOCAL.equals(scope) || (url.getParameter(LOCAL_PROTOCOL, false))) {   // 如果由 map 构造出来的 url 的 scope 属性为 local 或者 injvm 属性为 true，就是 InjvmRefer
            // if it's declared as local reference
            // 'scope=local' is equivalent to 'injvm=true', injvm will be deprecated in the future release
            return true;
        } else if (SCOPE_REMOTE.equals(scope)) {    // 如果由 map 构造出来的 url 的 scope 属性为 remote，就不是 InjvmRefer
            // it's declared as remote reference
            return false;
        } else if (url.getParameter(GENERIC_KEY, false)) {  // 如果由 map 构造出来的 url 的 generic 属性为 true，就不是 InjvmRefer
            // generic invocation is not local reference
            return false;
        } else if (getExporter(exporterMap, url) != null) { // 如果能够 exporterMap 中获取到 url 对应的 export，就是 InjvmRefer
            // by default, go through local reference if there's the service exposed locally
            return true;
        } else {
            return false;
        }
    }
}
