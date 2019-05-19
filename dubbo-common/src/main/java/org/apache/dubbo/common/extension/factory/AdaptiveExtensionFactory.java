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
package org.apache.dubbo.common.extension.factory;

import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.ExtensionFactory;
import org.apache.dubbo.common.extension.ExtensionLoader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AdaptiveExtensionFactory
 */
@Adaptive
public class AdaptiveExtensionFactory implements ExtensionFactory {
    // SpiExtensionFactory 和 SpringExtensionFactory
    private final List<ExtensionFactory> factories;

    public AdaptiveExtensionFactory() {
        // loader 为 AdaptiveExtensionFactory 实例
        ExtensionLoader<ExtensionFactory> loader = ExtensionLoader.getExtensionLoader(ExtensionFactory.class);
        List<ExtensionFactory> list = new ArrayList<ExtensionFactory>();
        // 遍历 org.apache.dubbo.common.extension.ExtensionFactory 对应配置文件下的所指定的类的名字
        for (String name : loader.getSupportedExtensions()) {
            // 通过 loader 获取 Extension 实例添加到 list 集合中
            list.add(loader.getExtension(name));
        }
        factories = Collections.unmodifiableList(list);
    }

    /**
     * 获取 Extension
     *
     * @param type object type. 为 method 参数的类型
     * @param name object name. 为 setter 方法所对应的属性的名字
     * @param <T>
     * @return
     */
    @Override
    public <T> T getExtension(Class<T> type, String name) {
        // 这里的 factories 对应不同的 Extension 加载策略
        // SpiExtensionFactory 和 SpringExtensionFactory
        // 这里可以看出来 AdaptiveExtensionFactory 就类似于一个适配器
        for (ExtensionFactory factory : factories) {
            T extension = factory.getExtension(type, name);
            if (extension != null) {
                return extension;
            }
        }
        return null;
    }

}
