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

package org.apache.dubbo.rpc.protocol.dubbo;

import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.Codec2;
import org.apache.dubbo.remoting.buffer.ChannelBuffer;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.exchange.Response;
import org.apache.dubbo.remoting.exchange.support.MultiMessage;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.RpcResult;

import java.io.IOException;

import static org.apache.dubbo.common.constants.RpcConstants.INPUT_KEY;
import static org.apache.dubbo.common.constants.RpcConstants.OUTPUT_KEY;

public final class DubboCountCodec implements Codec2 {
    // 通过 DubboCodec 进行
    private DubboCodec codec = new DubboCodec();

    @Override
    public void encode(Channel channel, ChannelBuffer buffer, Object msg) throws IOException {
        codec.encode(channel, buffer, msg);
    }

    @Override
    public Object decode(Channel channel, ChannelBuffer buffer) throws IOException {
        int save = buffer.readerIndex();    // 保存 buffer 的 readerIndex
        MultiMessage result = MultiMessage.create();    // 工厂方法创建 MultiMessage 实例，非单例，用于承载解析出来的结果（request 或者 response）
        do {
            Object obj = codec.decode(channel, buffer); // 解析出来的内容，为 request 或者 response
            if (Codec2.DecodeResult.NEED_MORE_INPUT == obj) {   // 这里说明当前 buffer 中的数据还不足以解析出一个完整的信息
                buffer.readerIndex(save);   // 恢复 buffer 的 readerIndex
                break;
            } else {
                result.addMessage(obj); // 保存解析出来的结果
                logMessageLength(obj, buffer.readerIndex() - save); // 将读入或者写出的数据长度记录到 RpcInvocation 实例中
                save = buffer.readerIndex();    // 重新备份 readerIndex 的值
            }
        } while (true); // 这个循环会一次性将全部的数据表解析出来保存到 result，知道剩余的字节不够一个完整的数据包
        if (result.isEmpty()) {
            return Codec2.DecodeResult.NEED_MORE_INPUT; // 这里表明没有解析出来一个完整的数据包
        }
        if (result.size() == 1) {
            return result.get(0);   // 单独返回
        }
        return result;  // 整体返回
    }

    // result 为解析出来的结果，bytes 为解析出来的结果的字节长度
    private void logMessageLength(Object result, int bytes) {
        if (bytes <= 0) {
            return;
        }
        if (result instanceof Request) {
            try {
                ((RpcInvocation) ((Request) result).getData()).setAttachment(INPUT_KEY, String.valueOf(bytes)); // input -> 数据长度
            } catch (Throwable e) {
                /* ignore */
            }
        } else if (result instanceof Response) {
            try {
                ((RpcResult) ((Response) result).getResult()).setAttachment(OUTPUT_KEY, String.valueOf(bytes)); // output -> 数据长度
            } catch (Throwable e) {
                /* ignore */
            }
        }
    }

}
