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
package org.apache.dubbo.rpc.filter;

import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;

import java.util.HashMap;
import java.util.Map;

import static org.apache.dubbo.common.constants.ClusterConstants.TAG_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROVIDER;
import static org.apache.dubbo.common.constants.CommonConstants.REMOTE_APPLICATION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.common.constants.RpcConstants.ASYNC_KEY;
import static org.apache.dubbo.common.constants.RpcConstants.DUBBO_VERSION_KEY;
import static org.apache.dubbo.common.constants.RpcConstants.FORCE_USE_TAG;
import static org.apache.dubbo.common.constants.RpcConstants.TOKEN_KEY;


/**
 * ContextFilter set the provider RpcContext with invoker, invocation, local port it is using and host for
 * current execution thread.
 *
 * @see RpcContext
 */
@Activate(group = PROVIDER, order = -10000)
public class ContextFilter implements Filter {  // 此 filter 主要是移除相关的一些参数，并保存一些属性到 RpcContext 中

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        Map<String, String> attachments = invocation.getAttachments();
        if (attachments != null) {
            attachments = new HashMap<>(attachments);
            attachments.remove(PATH_KEY);   // path
            attachments.remove(INTERFACE_KEY);  // interface
            attachments.remove(GROUP_KEY);  // group
            attachments.remove(VERSION_KEY);    // version
            attachments.remove(DUBBO_VERSION_KEY);  // dubbo
            attachments.remove(TOKEN_KEY);  // token
            attachments.remove(TIMEOUT_KEY);    // timeout
            // Remove async property to avoid being passed to the following invoke chain.
            attachments.remove(ASYNC_KEY);  // async
            attachments.remove(TAG_KEY);    // tag
            attachments.remove(FORCE_USE_TAG);  // dubbo.force.tag
        }
        RpcContext.getContext()
                .setInvoker(invoker)
                .setInvocation(invocation)
//                .setAttachments(attachments)  // merged from dubbox
                .setLocalAddress(invoker.getUrl().getHost(), invoker.getUrl().getPort())
                .setRemoteApplicationName(invoker.getUrl().getParameter(REMOTE_APPLICATION_KEY));

        // merged from dubbox
        // we may already added some attachments into RpcContext before this filter (e.g. in rest protocol)
        if (attachments != null) {
            if (RpcContext.getContext().getAttachments() != null) {
                RpcContext.getContext().getAttachments().putAll(attachments);   // 追加
            } else {
                RpcContext.getContext().setAttachments(attachments);    // 覆盖
            }
        }

        if (invocation instanceof RpcInvocation) {
            ((RpcInvocation) invocation).setInvoker(invoker);   // invoker 为下一个 invoker，保存到 invocation 中
        }
        try {
            return invoker.invoke(invocation);  // 向下传递
        } finally {
            // IMPORTANT! For async scenario, we must remove context from current thread, so we always create a new RpcContext for the next invoke for the same thread.
            RpcContext.removeContext();
            RpcContext.removeServerContext();
        }
    }

    // 如果 filter.invoker 方法返回的结果是 AsyncRpcResult 类型，在响应结果时，调用此方法
    @Override
    public Result onResponse(Result result, Invoker<?> invoker, Invocation invocation) {
        // pass attachments to result
        // 仅仅是将 ServerContext 的部分附件添加到 Result 中
        result.addAttachments(RpcContext.getServerContext().getAttachments());
        return result;
    }
}
