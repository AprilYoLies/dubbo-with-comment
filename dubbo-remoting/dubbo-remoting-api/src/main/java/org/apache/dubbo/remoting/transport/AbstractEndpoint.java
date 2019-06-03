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
package org.apache.dubbo.remoting.transport;

import org.apache.dubbo.common.Resetable;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.constants.RemotingConstants;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Codec;
import org.apache.dubbo.remoting.Codec2;
import org.apache.dubbo.remoting.transport.codec.CodecAdapter;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_TIMEOUT;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;

/**
 * AbstractEndpoint
 */
public abstract class AbstractEndpoint extends AbstractPeer implements Resetable {

    private static final Logger logger = LoggerFactory.getLogger(AbstractEndpoint.class);

    // 这里保存的其实是 org.apache.dubbo.rpc.protocol.dubbo.DubboCountCodec
    private Codec2 codec;

    private int timeout;

    private int connectTimeout;
    // 向上传递 url 和 handler 参数，根据 url 获取 codec、timeout、connectTimeout 等参数
    public AbstractEndpoint(URL url, ChannelHandler handler) {
        super(url, handler);    // 向上传递 url 和 handler 参数
        // 保存 url 中的指定参数值
        this.codec = getChannelCodec(url);  // 根据 url 的参数指定 codec 属性
        this.timeout = url.getPositiveParameter(TIMEOUT_KEY, DEFAULT_TIMEOUT);  // timeout -> 1000
        this.connectTimeout = url.getPositiveParameter(RemotingConstants.CONNECT_TIMEOUT_KEY, RemotingConstants.DEFAULT_CONNECT_TIMEOUT); // connect.timeout -> 3000
    }

    // 这里实际获取到的是 org.apache.dubbo.rpc.protocol.dubbo.DubboCountCodec
    protected static Codec2 getChannelCodec(URL url) {
        // 尝试从 url 中获取 codec 参数，如果没有的话就直接使用 telnet
        String codecName = url.getParameter(RemotingConstants.CODEC_KEY, "telnet");
        // 优先从 Codec2 SPI 接口对应的实现中获取 extension
        if (ExtensionLoader.getExtensionLoader(Codec2.class).hasExtension(codecName)) {
            return ExtensionLoader.getExtensionLoader(Codec2.class).getExtension(codecName);
        } else {
            // Codec2 SPI 接口中没有 codecName 对应的 extension，那就从 Codec SPI 接口对应的实现中获取 extension
            return new CodecAdapter(ExtensionLoader.getExtensionLoader(Codec.class)
                    .getExtension(codecName));
        }
    }

    @Override
    public void reset(URL url) {
        if (isClosed()) {
            throw new IllegalStateException("Failed to reset parameters "
                    + url + ", cause: Channel closed. channel: " + getLocalAddress());
        }
        try {
            if (url.hasParameter(TIMEOUT_KEY)) {
                int t = url.getParameter(TIMEOUT_KEY, 0);
                if (t > 0) {
                    this.timeout = t;
                }
            }
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
        }
        try {
            if (url.hasParameter(RemotingConstants.CONNECT_TIMEOUT_KEY)) {
                int t = url.getParameter(RemotingConstants.CONNECT_TIMEOUT_KEY, 0);
                if (t > 0) {
                    this.connectTimeout = t;
                }
            }
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
        }
        try {
            if (url.hasParameter(RemotingConstants.CODEC_KEY)) {
                this.codec = getChannelCodec(url);
            }
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
        }
    }

    @Deprecated
    public void reset(org.apache.dubbo.common.Parameters parameters) {
        reset(getUrl().addParameters(parameters.getParameters()));
    }

    protected Codec2 getCodec() {
        return codec;
    }

    protected int getTimeout() {
        return timeout;
    }

    protected int getConnectTimeout() {
        return connectTimeout;
    }

}
