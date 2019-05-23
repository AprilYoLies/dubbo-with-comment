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

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.constants.RemotingConstants;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.serialize.ObjectInput;
import org.apache.dubbo.common.serialize.Serialization;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.apache.dubbo.common.serialize.Constants.COMPACTED_JAVA_SERIALIZATION_ID;
import static org.apache.dubbo.common.serialize.Constants.JAVA_SERIALIZATION_ID;
import static org.apache.dubbo.common.serialize.Constants.NATIVE_JAVA_SERIALIZATION_ID;

public class CodecSupport {

    private static final Logger logger = LoggerFactory.getLogger(CodecSupport.class);
    private static Map<Byte, Serialization> ID_SERIALIZATION_MAP = new HashMap<Byte, Serialization>();  // ID_SERIALIZATION_MAP 映射 serialization 的 id 字节和 serialization 实例
    private static Map<Byte, String> ID_SERIALIZATIONNAME_MAP = new HashMap<Byte, String>();    // ID_SERIALIZATIONNAME_MAP 映射 serialization 的 id 字节和 serialization 名字

    static {
        Set<String> supportedExtensions = ExtensionLoader.getExtensionLoader(Serialization.class).getSupportedExtensions(); // 获取的就是全部的 extensionClass
        for (String name : supportedExtensions) {
            Serialization serialization = ExtensionLoader.getExtensionLoader(Serialization.class).getExtension(name); // 获取 name 对应的 extension
            byte idByte = serialization.getContentTypeId(); // 获取 serialization 实例对应的 id 字节
            if (ID_SERIALIZATION_MAP.containsKey(idByte)) {
                logger.error("Serialization extension " + serialization.getClass().getName()
                        + " has duplicate id to Serialization extension "
                        + ID_SERIALIZATION_MAP.get(idByte).getClass().getName()
                        + ", ignore this Serialization extension");
                continue;
            }
            ID_SERIALIZATION_MAP.put(idByte, serialization);    // ID_SERIALIZATION_MAP 映射 serialization 的 id 字节和 serialization 实例
            ID_SERIALIZATIONNAME_MAP.put(idByte, name);     // ID_SERIALIZATIONNAME_MAP 映射 serialization 的 id 字节和 serialization 名字
        }
    }

    private CodecSupport() {
    }

    public static Serialization getSerializationById(Byte id) {
        return ID_SERIALIZATION_MAP.get(id);
    }

    public static Serialization getSerialization(URL url) {
        return ExtensionLoader.getExtensionLoader(Serialization.class).getExtension(
                // 获取 Serialization SPI 接口的 extension 拓展类，优先获取 serialization，没有获取到的话就使用 hessian2
                url.getParameter(RemotingConstants.SERIALIZATION_KEY, RemotingConstants.DEFAULT_REMOTING_SERIALIZATION));
    }

    public static Serialization getSerialization(URL url, Byte id) throws IOException {
        Serialization serialization = getSerializationById(id); // 从缓存中获取 serialization 实例
        String serializationName = url.getParameter(RemotingConstants.SERIALIZATION_KEY, RemotingConstants.DEFAULT_REMOTING_SERIALIZATION); // 获取 serialization 默认 hessian2
        // Check if "serialization id" passed from network matches the id on this side(only take effect for JDK serialization), for security purpose.
        if (serialization == null  // 如果是条件中的三种 serialization，但是 url 指定的 serialization 不和 id 对应的名字一致
                || ((id == JAVA_SERIALIZATION_ID || id == NATIVE_JAVA_SERIALIZATION_ID || id == COMPACTED_JAVA_SERIALIZATION_ID)
                && !(serializationName.equals(ID_SERIALIZATIONNAME_MAP.get(id))))) {
            throw new IOException("Unexpected serialization id:" + id + " received from network, please check if the peer send the right id.");
        }
        return serialization;
    }

    public static ObjectInput deserialize(URL url, InputStream is, byte proto) throws IOException {
        Serialization s = getSerialization(url, proto); // 根据
        return s.deserialize(url, is);  // 根本是通过 Hessian2ObjectInput 进行反序列化
    }
}
