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
package org.apache.dubbo.config.spring.schema;

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.ArgumentConfig;
import org.apache.dubbo.config.ConsumerConfig;
import org.apache.dubbo.config.MethodConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.ProviderConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.spring.ReferenceBean;
import org.apache.dubbo.config.spring.ServiceBean;

import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.support.ManagedList;
import org.springframework.beans.factory.support.ManagedMap;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.BeanDefinitionParser;
import org.springframework.beans.factory.xml.ParserContext;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.apache.dubbo.common.constants.CommonConstants.HIDE_KEY_PREFIX;

/**
 * AbstractBeanDefinitionParser
 *
 * @export
 */
public class DubboBeanDefinitionParser implements BeanDefinitionParser {

    private static final Logger logger = LoggerFactory.getLogger(DubboBeanDefinitionParser.class);
    private static final Pattern GROUP_AND_VERION = Pattern.compile("^[\\-.0-9_a-zA-Z]+(\\:[\\-.0-9_a-zA-Z]+)?$");
    private final Class<?> beanClass;
    private final boolean required;

    // beanClass 为各种 Config 的 class 对象，比如 ApplicationConfig.class
    public DubboBeanDefinitionParser(Class<?> beanClass, boolean required) {
        this.beanClass = beanClass;
        this.required = required;
    }

    @SuppressWarnings("unchecked")
    private static BeanDefinition parse(Element element, ParserContext parserContext, Class<?> beanClass, boolean required) {
        // 用于承载被解析出来的标签所对应的 bean 的信息
        RootBeanDefinition beanDefinition = new RootBeanDefinition();
        beanDefinition.setBeanClass(beanClass);
        beanDefinition.setLazyInit(false);
        // 获取 id 属性
        String id = element.getAttribute("id");
        // id 属性为空后者空串
        if (StringUtils.isEmpty(id) && required) {
            String generatedBeanName = element.getAttribute("name");
            if (StringUtils.isEmpty(generatedBeanName)) {
                // 如果是调用的引用了 ProtocolConfig.class 的 DubboBeanDefinitionParser，并且没有 id 和 name 属性，就将解析出来的 beanDefinition 的名字叫做 dubbo
                if (ProtocolConfig.class.equals(beanClass)) {
                    generatedBeanName = "dubbo";
                } else {
                    // 不是 引用了 ProtocolConfig.class 的 DubboBeanDefinitionParser，就将 beanDefinition 命名为 interface 属性的值
                    generatedBeanName = element.getAttribute("interface");
                }
            }
            // 将 beanClass 的全限定名作为 beanDefinition 的名字
            if (StringUtils.isEmpty(generatedBeanName)) {
                generatedBeanName = beanClass.getName();
            }
            // id 也用这个 beanDefinition 的名字
            id = generatedBeanName;
            int counter = 2;
            // 如果已经注册过这个 id 的 beanDefinition，就将 id 后边加上一个数字
            while (parserContext.getRegistry().containsBeanDefinition(id)) {
                id = generatedBeanName + (counter++);
            }
        }
        // 如果 id 不为空，也不为空串
        if (id != null && id.length() > 0) {
            // 已注册过此 id 则报错
            if (parserContext.getRegistry().containsBeanDefinition(id)) {
                throw new IllegalStateException("Duplicate spring bean id " + id);
            }
            // 将 id 和 beanDefinition 注册，此 beanDefinition 用于承载被解析的标签所对应的 bean 的信息
            parserContext.getRegistry().registerBeanDefinition(id, beanDefinition);
            // 为这个 beanDefinition 添加个 id 属性值
            beanDefinition.getPropertyValues().addPropertyValue("id", id);
        }
        // 如果是调用的引用了 ProtocolConfig.class 的 DubboBeanDefinitionParser
        if (ProtocolConfig.class.equals(beanClass)) {
            // 通过 beanDefinition 的 name 获取 beanDefinition
            for (String name : parserContext.getRegistry().getBeanDefinitionNames()) {
                BeanDefinition definition = parserContext.getRegistry().getBeanDefinition(name);
                PropertyValue property = definition.getPropertyValues().getPropertyValue("protocol");
                if (property != null) {
                    Object value = property.getValue();
                    // 如果在已经注册的 beanDefinition 中，具有属性 protocol，属性值为 ProtocolConfig 对象，且这个 Protocol 对象的 name 属性和现在注册的
                    // 这个 beanDefinition 的 id 相等，那么就就将这个 protocol 属性的值修改为当前 beanDefinition 的运行时引用（估计是为了避免循环引用的问题）
                    if (value instanceof ProtocolConfig && id.equals(((ProtocolConfig) value).getName())) {
                        definition.getPropertyValues().addPropertyValue("protocol", new RuntimeBeanReference(id));
                    }
                }
            }
        } else if (ServiceBean.class.equals(beanClass)) {
            // 此处解析 service 标签
            String className = element.getAttribute("class");
            // 如果 service 标签有 class 属性
            if (className != null && className.length() > 0) {
                // 此 classDefinition 用于承载 service 标签所对应的 class 属性值所代表的 class 对象
                RootBeanDefinition classDefinition = new RootBeanDefinition();
                classDefinition.setBeanClass(ReflectUtils.forName(className));
                classDefinition.setLazyInit(false);
                // 解析当前标签元素的子元素信息，用 classDefinition 承载解析出来的信息
                parseProperties(element.getChildNodes(), classDefinition);
                // 为 beanDefinition 添加一个 ref 属性,值为 service 标签 class 属性所对应的 classDefinition
                beanDefinition.getPropertyValues().addPropertyValue("ref", new BeanDefinitionHolder(classDefinition, id + "Impl"));
            }
        } else if (ProviderConfig.class.equals(beanClass)) {
            // 此处解析 provider 标签
            parseNested(element, parserContext, ServiceBean.class, true, "service", "provider", id, beanDefinition);
        } else if (ConsumerConfig.class.equals(beanClass)) {
            // 此处解析 consumer 标签
            parseNested(element, parserContext, ReferenceBean.class, false, "reference", "consumer", id, beanDefinition);
        }
        // 用于保存 beanClass 的属性
        Set<String> props = new HashSet<>();
        ManagedMap parameters = null;
        // 遍历 beanClass 的所有方法，根据相应的属性为 beanDefinition 进行填充
        for (Method setter : beanClass.getMethods()) {
            String name = setter.getName();
            // 如果是 public 修饰的参数个数为一的 setter 方法
            if (name.length() > 3 && name.startsWith("set")
                    && Modifier.isPublic(setter.getModifiers())
                    && setter.getParameterTypes().length == 1) {
                // 唯一的参数类型
                Class<?> type = setter.getParameterTypes()[0];
                // setter 方法所对应的属性
                String beanProperty = name.substring(3, 4).toLowerCase() + name.substring(4);
                // 看这个测试用例就知道这个函数的作用了，均能通过测试
                // public void testCamelToSplitName() throws Exception {
                //     assertEquals("ab-cd-ef", StringUtils.camelToSplitName("abCdEf", "-"));
                //     assertEquals("ab-cd-ef", StringUtils.camelToSplitName("AbCdEf", "-"));
                //     assertEquals("ab-cd-ef", StringUtils.camelToSplitName("ab-cd-ef", "-"));
                //     assertEquals("abcdef", StringUtils.camelToSplitName("abcdef", "-"));
                // }
                String property = StringUtils.camelToSplitName(beanProperty, "-");
                props.add(property);
                // check the setter/getter whether match
                Method getter = null;
                try {
                    // 获取相应的 getter 方法
                    getter = beanClass.getMethod("get" + name.substring(3), new Class<?>[0]);
                } catch (NoSuchMethodException e) {
                    try {
                        // 没有 getter 方法，就获取 is 方法
                        getter = beanClass.getMethod("is" + name.substring(3), new Class<?>[0]);
                    } catch (NoSuchMethodException e2) {
                        // ignore, there is no need any log here since some class implement the interface: EnvironmentAware,
                        // ApplicationAware, etc. They only have setter method, otherwise will cause the error log during application start up.
                    }
                }
                // 如果 getter 方法为空，或者非 public 修饰，或者返回值类型不一致，就继续下一个方法
                if (getter == null
                        || !Modifier.isPublic(getter.getModifiers())
                        || !type.equals(getter.getReturnType())) {
                    continue;
                }
                // 如果是 parameters 属性
                if ("parameters".equals(property)) {
                    // 为 beanDefinition 填充 parameters
                    parameters = parseParameters(element.getChildNodes(), beanDefinition);
                } else if ("methods".equals(property)) {
                    // 如果是 methods 属性，为 beanDefinition 填充 methods
                    parseMethods(id, element.getChildNodes(), beanDefinition, parserContext);
                } else if ("arguments".equals(property)) {
                    // 如果是 arguments 属性，为 beanDefinition 填充 arguments
                    parseArguments(id, element.getChildNodes(), beanDefinition, parserContext);
                } else {
                    // 非上述三种情况，直接获取对应的属性值
                    String value = element.getAttribute(property);
                    if (value != null) {
                        value = value.trim();
                        // 获取的属性值不为空，不为空串
                        if (value.length() > 0) {
                            // 如果 getter 方法对应的属性为 registry，且属性值为 N/A
                            if ("registry".equals(property) && RegistryConfig.NO_AVAILABLE.equalsIgnoreCase(value)) {
                                // 直接构建 RegistryConfig 对象，设置 address 为 N/A
                                RegistryConfig registryConfig = new RegistryConfig();
                                registryConfig.setAddress(RegistryConfig.NO_AVAILABLE);
                                // 为 beanDefinition 添加这个 RegistryConfig 属性值，键为 beanProperty（即 getter 方法去掉 get 子串，并首字母变小写的结果）
                                beanDefinition.getPropertyValues().addPropertyValue(beanProperty, registryConfig);
                            } else if ("provider".equals(property) || "registry".equals(property) || ("protocol".equals(property) && ServiceBean.class.equals(beanClass))) {
                                // 如果 setter 方法所代表的属性为 provider、registry（相应的值不为 N/A）或者 protocol 并且当前解析的是 service 标签
                                /**
                                 * For 'provider' 'protocol' 'registry', keep literal value (should be id/name) and set the value to 'registryIds' 'providerIds' protocolIds'
                                 * The following process should make sure each id refers to the corresponding instance, here's how to find the instance for different use cases:
                                 * 1. Spring, check existing bean by id, see{@link ServiceBean#afterPropertiesSet()}; then try to use id to find configs defined in remote Config Center
                                 * 2. API, directly use id to find configs defined in remote Config Center; if all config instances are defined locally, please use {@link org.apache.dubbo.config.ServiceConfig#setRegistries(List)}
                                 */
                                beanDefinition.getPropertyValues().addPropertyValue(beanProperty + "Ids", value);
                            } else {
                                Object reference;
                                if (isPrimitive(type)) {
                                    if ("async".equals(property) && "false".equals(value)
                                            || "timeout".equals(property) && "0".equals(value)
                                            || "delay".equals(property) && "0".equals(value)
                                            || "version".equals(property) && "0.0.0".equals(value)
                                            || "stat".equals(property) && "-1".equals(value)
                                            || "reliable".equals(property) && "false".equals(value)) {
                                        // backward compatibility for the default value in old version's xsd
                                        value = null;
                                    }
                                    reference = value;
                                } else if ("onreturn".equals(property)) {
                                    int index = value.lastIndexOf(".");
                                    String returnRef = value.substring(0, index);
                                    String returnMethod = value.substring(index + 1);
                                    reference = new RuntimeBeanReference(returnRef);
                                    beanDefinition.getPropertyValues().addPropertyValue("onreturnMethod", returnMethod);
                                } else if ("onthrow".equals(property)) {
                                    int index = value.lastIndexOf(".");
                                    String throwRef = value.substring(0, index);
                                    String throwMethod = value.substring(index + 1);
                                    reference = new RuntimeBeanReference(throwRef);
                                    beanDefinition.getPropertyValues().addPropertyValue("onthrowMethod", throwMethod);
                                } else if ("oninvoke".equals(property)) {
                                    int index = value.lastIndexOf(".");
                                    String invokeRef = value.substring(0, index);
                                    String invokeRefMethod = value.substring(index + 1);
                                    reference = new RuntimeBeanReference(invokeRef);
                                    beanDefinition.getPropertyValues().addPropertyValue("oninvokeMethod", invokeRefMethod);
                                } else {
                                    if ("ref".equals(property) && parserContext.getRegistry().containsBeanDefinition(value)) {
                                        BeanDefinition refBean = parserContext.getRegistry().getBeanDefinition(value);
                                        if (!refBean.isSingleton()) {
                                            throw new IllegalStateException("The exported service ref " + value + " must be singleton! Please set the " + value + " bean scope to singleton, eg: <bean id=\"" + value + "\" scope=\"singleton\" ...>");
                                        }
                                    }
                                    reference = new RuntimeBeanReference(value);
                                }
                                beanDefinition.getPropertyValues().addPropertyValue(beanProperty, reference);
                            }
                        }
                    }
                }
            }
        }
        NamedNodeMap attributes = element.getAttributes();
        int len = attributes.getLength();
        for (int i = 0; i < len; i++) {
            Node node = attributes.item(i);
            String name = node.getLocalName();
            if (!props.contains(name)) {
                if (parameters == null) {
                    parameters = new ManagedMap();
                }
                String value = node.getNodeValue();
                parameters.put(name, new TypedStringValue(value, String.class));
            }
        }
        if (parameters != null) {
            beanDefinition.getPropertyValues().addPropertyValue("parameters", parameters);
        }
        return beanDefinition;
    }

    private static boolean isPrimitive(Class<?> cls) {
        return cls.isPrimitive() || cls == Boolean.class || cls == Byte.class
                || cls == Character.class || cls == Short.class || cls == Integer.class
                || cls == Long.class || cls == Float.class || cls == Double.class
                || cls == String.class || cls == Date.class || cls == Class.class;
    }

    /**
     * 此方法在解析 provider 标签或者 consumer 标签时会使用到
     *
     * @param element        根标签
     * @param parserContext  解析上下文环境
     * @param beanClass      分别对应 ServiceBean 和 ReferenceBean
     * @param required       由上层函数传入
     * @param tag            分别对应 service 和 reference
     * @param property       分别对应 provider 和 consumer
     * @param ref            即当前待解析标签的 id
     * @param beanDefinition 当前待解析标签对应的 beanDefinition
     */
    private static void parseNested(Element element, ParserContext parserContext, Class<?> beanClass, boolean required, String tag, String property, String ref, BeanDefinition beanDefinition) {
        // 获取子标签
        NodeList nodeList = element.getChildNodes();
        if (nodeList != null && nodeList.getLength() > 0) {
            boolean first = true;
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    // 这里就是分别解析如下两种标签的情况，子标签为 service 或者 reference
                    // <provider>
                    //         <service><service/>
                    // <provider/>
                    //
                    // <consumer>
                    //         <reference><reference/>
                    // <consumer/>
                    if (tag.equals(node.getNodeName())
                            || tag.equals(node.getLocalName())) {
                        // 第一次解析的时候，考虑下根标签的 default 属性
                        if (first) {
                            first = false;
                            // 根标签没有 default 属性，或者为空串
                            String isDefault = element.getAttribute("default");
                            if (StringUtils.isEmpty(isDefault)) {
                                // 将 default 置为 false
                                beanDefinition.getPropertyValues().addPropertyValue("default", "false");
                            }
                        }
                        // 真正解析这个子标签，其实就是个递归调用的过程
                        BeanDefinition subDefinition = parse((Element) node, parserContext, beanClass, required);
                        if (subDefinition != null && ref != null && ref.length() > 0) {
                            // 为子标签添加一个对父节点标签所对应 bean 的引用，属性名为 provider 或者 consumer（由当前解析的父标签所决定）
                            subDefinition.getPropertyValues().addPropertyValue(property, new RuntimeBeanReference(ref));
                        }
                    }
                }
            }
        }
    }

    private static void parseProperties(NodeList nodeList, RootBeanDefinition beanDefinition) {
        if (nodeList != null && nodeList.getLength() > 0) {
            for (int i = 0; i < nodeList.getLength(); i++) {
                // node 代表子标签
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    // 为 property 标签
                    if ("property".equals(node.getNodeName())
                            || "property".equals(node.getLocalName())) {
                        String name = ((Element) node).getAttribute("name");
                        // 标签的 name 属性不为空或者空串
                        if (name != null && name.length() > 0) {
                            String value = ((Element) node).getAttribute("value");
                            String ref = ((Element) node).getAttribute("ref");
                            // property 标签的属性值可能为 value 数值型和 ref 引用型
                            if (value != null && value.length() > 0) {
                                // 将 解析出来的 property 属性存入 beanDefinition 中
                                beanDefinition.getPropertyValues().addPropertyValue(name, value);
                            } else if (ref != null && ref.length() > 0) {
                                // 将 解析出来的 property 属性存入 beanDefinition 中
                                beanDefinition.getPropertyValues().addPropertyValue(name, new RuntimeBeanReference(ref));
                            } else {
                                throw new UnsupportedOperationException("Unsupported <property name=\"" + name + "\"> sub tag, Only supported <property name=\"" + name + "\" ref=\"...\" /> or <property name=\"" + name + "\" value=\"...\" />");
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 如果当前解析的标签有完成的 setter getter is 方法，就用调用此方法来解析子 parameter 标签，返回值为解析出来的 kv 对
     *
     * @param nodeList
     * @param beanDefinition
     * @return
     */
    @SuppressWarnings("unchecked")
    private static ManagedMap parseParameters(NodeList nodeList, RootBeanDefinition beanDefinition) {
        if (nodeList != null && nodeList.getLength() > 0) {
            ManagedMap parameters = null;
            for (int i = 0; i < nodeList.getLength(); i++) {
                // 获取子标签元素
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    // 如果子标签为 parameter
                    if ("parameter".equals(node.getNodeName())
                            || "parameter".equals(node.getLocalName())) {
                        if (parameters == null) {
                            parameters = new ManagedMap();
                        }
                        String key = ((Element) node).getAttribute("key");
                        String value = ((Element) node).getAttribute("value");
                        // 如果 parameter 标签存在 hide 属性为 true，就在 key 属性值前边加一个'.'
                        boolean hide = "true".equals(((Element) node).getAttribute("hide"));
                        if (hide) {
                            key = HIDE_KEY_PREFIX + key;
                        }
                        // 用 parameters 存储解析出来的 parameter 标签的 key 和 value
                        parameters.put(key, new TypedStringValue(value, String.class));
                    }
                }
            }
            return parameters;
        }
        return null;
    }

    /**
     * 解析 method 子标签
     *
     * @param id             当前解析的 beanDefinition 的 id
     * @param nodeList       子标签元素
     * @param beanDefinition 当前解析的 beanDefinition
     * @param parserContext  解析上下文环境
     */
    @SuppressWarnings("unchecked")
    private static void parseMethods(String id, NodeList nodeList, RootBeanDefinition beanDefinition,
                                     ParserContext parserContext) {
        if (nodeList != null && nodeList.getLength() > 0) {
            ManagedList methods = null;
            for (int i = 0; i < nodeList.getLength(); i++) {
                // 获取每一个子标签元素
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    Element element = (Element) node;
                    // 如果是 method 标签
                    if ("method".equals(node.getNodeName()) || "method".equals(node.getLocalName())) {
                        String methodName = element.getAttribute("name");
                        // method 标签必须要有 name 属性
                        if (StringUtils.isEmpty(methodName)) {
                            throw new IllegalStateException("<dubbo:method> name attribute == null");
                        }
                        if (methods == null) {
                            methods = new ManagedList();
                        }
                        // 递归调用解析，此时解析的是 method 标签，返回代表 method 标签的 methodBeanDefinition
                        BeanDefinition methodBeanDefinition = parse(((Element) node),
                                parserContext, MethodConfig.class, false);
                        // 方法标签 beanDefinition 的命名方式
                        String name = id + "." + methodName;
                        // 用 BeanDefinitionHolder 对象来承载解析出来的 methodBeanDefinition 实例
                        BeanDefinitionHolder methodBeanDefinitionHolder = new BeanDefinitionHolder(
                                methodBeanDefinition, name);
                        // methods 存储当前标签下的所有 method 标签所对应的 methodBeanDefinitionHolder
                        methods.add(methodBeanDefinitionHolder);
                    }
                }
            }
            if (methods != null) {
                // 将解析的 methods 结果存放到根节点所代表的 beanDefinition 中去
                beanDefinition.getPropertyValues().addPropertyValue("methods", methods);
            }
        }
    }

    /**
     * 跟 parseMethods 方法作用基本一致
     * @param id
     * @param nodeList
     * @param beanDefinition
     * @param parserContext
     */
    @SuppressWarnings("unchecked")
    private static void parseArguments(String id, NodeList nodeList, RootBeanDefinition beanDefinition,
                                       ParserContext parserContext) {
        if (nodeList != null && nodeList.getLength() > 0) {
            ManagedList arguments = null;
            for (int i = 0; i < nodeList.getLength(); i++) {
                // 获取每个子标签元素
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    Element element = (Element) node;
                    // 为 argument 标签
                    if ("argument".equals(node.getNodeName()) || "argument".equals(node.getLocalName())) {
                        String argumentIndex = element.getAttribute("index");
                        if (arguments == null) {
                            arguments = new ManagedList();
                        }
                        // 递归调用进行解析，这里解析的是 argument 标签，返回相应的 beanDefinition
                        BeanDefinition argumentBeanDefinition = parse(((Element) node),
                                parserContext, ArgumentConfig.class, false);
                        // argumentBeanDefinition 的命名规则
                        String name = id + "." + argumentIndex;
                        // 包装解析出来的 argumentBeanDefinition
                        BeanDefinitionHolder argumentBeanDefinitionHolder = new BeanDefinitionHolder(
                                argumentBeanDefinition, name);
                        arguments.add(argumentBeanDefinitionHolder);
                    }
                }
            }
            if (arguments != null) {
                // 将解析出来的结果封装到当前解析的父标签所对应的 beanDefinition 中去
                beanDefinition.getPropertyValues().addPropertyValue("arguments", arguments);
            }
        }
    }

    @Override
    public BeanDefinition parse(Element element, ParserContext parserContext) {
        // element 即标签元素如 <dubbo:application />,parserContext 为 spring 提供的标签解析上下文环境
        return parse(element, parserContext, beanClass, required);
    }

}
