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
package org.apache.dubbo.rpc.cluster.router.mock;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.router.AbstractRouter;

import java.util.ArrayList;
import java.util.List;

import static org.apache.dubbo.common.constants.ClusterConstants.INVOCATION_NEED_MOCK;
import static org.apache.dubbo.common.constants.ClusterConstants.MOCK_PROTOCOL;

/**
 * A specific Router designed to realize mock feature.
 * If a request is configured to use mock, then this router guarantees that only the invokers with protocol MOCK appear in final the invoker list, all other invokers will be excluded.
 */
public class MockInvokersSelector extends AbstractRouter {  // 此 Router 就会根据 invocation 的 invocation.need.mock 参数来决定是只要 mock 协议的 invoker 还是不要 mock 协议的 invoker

    public static final String NAME = "MOCK_ROUTER";
    private static final int MOCK_INVOKERS_DEFAULT_PRIORITY = Integer.MIN_VALUE;

    public MockInvokersSelector() {
        this.priority = MOCK_INVOKERS_DEFAULT_PRIORITY;
    }

    @Override   // 根据 invocation 的 invocation.need.mock 参数来决定是只要 mock 协议的 invoker 还是不要 mock 协议的 invoker
    public <T> List<Invoker<T>> route(final List<Invoker<T>> invokers,
                                      URL url, final Invocation invocation) throws RpcException {
        if (CollectionUtils.isEmpty(invokers)) {
            return invokers;
        }

        if (invocation.getAttachments() == null) {
            return getNormalInvokers(invokers); // 去掉协议为 mock 的项
        } else {
            String value = invocation.getAttachments().get(INVOCATION_NEED_MOCK);   // 根据 invocation.need.mock 的信息确定返回的 Invoker 的类型
            if (value == null) {
                return getNormalInvokers(invokers); // 去掉协议为 mock 的项
            } else if (Boolean.TRUE.toString().equalsIgnoreCase(value)) {
                return getMockedInvokers(invokers); // 这里就是只要协议为 mock 的项
            }
        }
        return invokers;
    }

    private <T> List<Invoker<T>> getMockedInvokers(final List<Invoker<T>> invokers) {
        if (!hasMockProviders(invokers)) {
            return null;
        }
        List<Invoker<T>> sInvokers = new ArrayList<Invoker<T>>(1);
        for (Invoker<T> invoker : invokers) {
            if (invoker.getUrl().getProtocol().equals(MOCK_PROTOCOL)) {
                sInvokers.add(invoker); // 筛选出协议为 mock 的项并返回
            }
        }
        return sInvokers;
    }

    private <T> List<Invoker<T>> getNormalInvokers(final List<Invoker<T>> invokers) {
        if (!hasMockProviders(invokers)) {  // 确定 invokers 中是否有 url 的 protocol 为 mock 的项
            return invokers;    // 如果没有，直接返回 invokers
        } else {
            List<Invoker<T>> sInvokers = new ArrayList<Invoker<T>>(invokers.size());
            for (Invoker<T> invoker : invokers) {
                if (!invoker.getUrl().getProtocol().equals(MOCK_PROTOCOL)) {
                    sInvokers.add(invoker); // 去掉协议为 mock 的项
                }
            }
            return sInvokers;
        }
    }

    // 确定 invokers 中是否有 url 的 protocol 为 mock 的项
    private <T> boolean hasMockProviders(final List<Invoker<T>> invokers) {
        boolean hasMockProvider = false;
        for (Invoker<T> invoker : invokers) {
            if (invoker.getUrl().getProtocol().equals(MOCK_PROTOCOL)) {
                hasMockProvider = true;
                break;
            }
        }
        return hasMockProvider;
    }

}
