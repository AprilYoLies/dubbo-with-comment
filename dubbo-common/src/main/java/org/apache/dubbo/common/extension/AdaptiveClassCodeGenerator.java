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
package org.apache.dubbo.common.extension;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.StringUtils;

/**
 * Code generator for Adaptive class
 */
public class AdaptiveClassCodeGenerator {

    private static final Logger logger = LoggerFactory.getLogger(AdaptiveClassCodeGenerator.class);

    private static final String CLASSNAME_INVOCATION = "org.apache.dubbo.rpc.Invocation";

    private static final String CODE_PACKAGE = "package %s;\n";

    private static final String CODE_IMPORTS = "import %s;\n";

    private static final String CODE_CLASS_DECLARATION = "public class %s$Adaptive implements %s {\n";

    private static final String CODE_METHOD_DECLARATION = "public %s %s(%s) %s {\n%s}\n";

    private static final String CODE_METHOD_ARGUMENT = "%s arg%d";

    private static final String CODE_METHOD_THROWS = "throws %s";

    private static final String CODE_UNSUPPORTED = "throw new UnsupportedOperationException(\"The method %s of interface %s is not adaptive method!\");\n";

    private static final String CODE_URL_NULL_CHECK = "if (arg%d == null) throw new IllegalArgumentException(\"url == null\");\n%s url = arg%d;\n";

    private static final String CODE_EXT_NAME_ASSIGNMENT = "String extName = %s;\n";

    private static final String CODE_EXT_NAME_NULL_CHECK = "if(extName == null) "
            + "throw new IllegalStateException(\"Failed to get extension (%s) name from url (\" + url.toString() + \") use keys(%s)\");\n";

    private static final String CODE_INVOCATION_ARGUMENT_NULL_CHECK = "if (arg%d == null) throw new IllegalArgumentException(\"invocation == null\"); "
            + "String methodName = arg%d.getMethodName();\n";


    private static final String CODE_EXTENSION_ASSIGNMENT = "%s extension = (%<s)%s.getExtensionLoader(%s.class).getExtension(extName);\n";

    private final Class<?> type;

    private String defaultExtName;

    public AdaptiveClassCodeGenerator(Class<?> type, String defaultExtName) {
        this.type = type;
        this.defaultExtName = defaultExtName;
    }

    /**
     * test if given type has at least one method annotated with <code>SPI</code>
     */
    private boolean hasAdaptiveMethod() {
        // 通过 Arrays.stream 生成 Stream 进行流式处理，目的是为了找到使用了 Adaptive 注解的方法
        return Arrays.stream(type.getMethods()).anyMatch(m -> m.isAnnotationPresent(Adaptive.class));
    }

    /**
     * generate and return class code
     */
    public String generate() {
        // no need to generate adaptive class since there's no adaptive method found.
        // 为了生成 AdaptiveExtensionClass，type 所代表的 SPI 接口必须要有 Adaptive 注解的方法
        if (!hasAdaptiveMethod()) {
            throw new IllegalStateException("No adaptive method exist on extension " + type.getName() + ", refuse to create the adaptive class!");
        }

        StringBuilder code = new StringBuilder();
        // package org.apache.dubbo.rpc;
        code.append(generatePackageInfo());
        // package org.apache.dubbo.rpc;
        // import org.apache.dubbo.common.extension.ExtensionLoader;
        code.append(generateImports());
        // package org.apache.dubbo.rpc;
        // import org.apache.dubbo.common.extension.ExtensionLoader;
        // public class Protocol$Adaptive implements org.apache.dubbo.rpc.Protocol {
        code.append(generateClassDeclaration());

        Method[] methods = type.getMethods();
        for (Method method : methods) {
            code.append(generateMethod(method));
        }

        // 最终生成的 code 信息
        // package org.apache.dubbo.rpc;
        // import org.apache.dubbo.common.extension.ExtensionLoader;
        // public class Protocol$Adaptive implements org.apache.dubbo.rpc.Protocol {
        //     public void destroy() {
        //         throw new UnsupportedOperationException("The method public abstract void org.apache.dubbo.rpc.Protocol.destroy() of interface org.apache.dubbo.rpc.Protocol is not adaptive method!");
        //
        //     public int getDefaultPort() {
        //         throw new UnsupportedOperationException("The method public abstract int org.apache.dubbo.rpc.Protocol.getDefaultPort() of interface org.apache.dubbo.rpc.Protocol is not adaptive method!");
        //
        //     public org.apache.dubbo.rpc.Exporter export(org.apache.dubbo.rpc.Invoker arg0) throws org.apache.dubbo.rpc.RpcException {
        //         if (arg0 == null) throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument == null");
        //         if (arg0.getUrl() == null)
        //             throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument getUrl() == null");
        //         org.apache.dubbo.common.URL url = arg0.getUrl();
        //         String extName = (url.getProtocol() == null ? "dubbo" : url.getProtocol());
        //         if (extName == null)
        //             throw new IllegalStateException("Failed to get extension (org.apache.dubbo.rpc.Protocol) name from url (" + url.toString() + ") use keys([protocol])");
        //         org.apache.dubbo.rpc.Protocol extension = (org.apache.dubbo.rpc.Protocol) ExtensionLoader.getExtensionLoader(org.apache.dubbo.rpc.Protocol.class).getExtension(extName);
        //         return extension.export(arg0);
        //
        //     public org.apache.dubbo.rpc.Invoker refer(java.lang.Class arg0, org.apache.dubbo.common.URL arg1) throws org.apache.dubbo.rpc.RpcException {
        //         if (arg1 == null) throw new IllegalArgumentException("url == null");
        //         org.apache.dubbo.common.URL url = arg1;
        //         String extName = (url.getProtocol() == null ? "dubbo" : url.getProtocol());
        //         if (extName == null)
        //             throw new IllegalStateException("Failed to get extension (org.apache.dubbo.rpc.Protocol) name from url (" + url.toString() + ") use keys([protocol])");
        //         org.apache.dubbo.rpc.Protocol extension = (org.apache.dubbo.rpc.Protocol) ExtensionLoader.getExtensionLoader(org.apache.dubbo.rpc.Protocol.class).getExtension(extName);
        //         return extension.refer(arg0, arg1);
        //     }
        // }

        code.append("}");

        if (logger.isDebugEnabled()) {
            logger.debug(code.toString());
        }
        return code.toString();
    }

    /**
     * generate package info
     */
    private String generatePackageInfo() {
        return String.format(CODE_PACKAGE, type.getPackage().getName());
    }

    /**
     * generate imports
     */
    private String generateImports() {
        return String.format(CODE_IMPORTS, ExtensionLoader.class.getName());
    }

    /**
     * generate class declaration
     */
    private String generateClassDeclaration() {
        return String.format(CODE_CLASS_DECLARATION, type.getSimpleName(), type.getCanonicalName());
    }

    /**
     * generate method not annotated with Adaptive with throwing unsupported exception
     */
    private String generateUnsupported(Method method) {
        return String.format(CODE_UNSUPPORTED, method, type.getName());
    }

    /**
     * get index of parameter with type URL
     */
    private int getUrlTypeIndex(Method method) {
        int urlTypeIndex = -1;
        Class<?>[] pts = method.getParameterTypes();
        for (int i = 0; i < pts.length; ++i) {
            // 从方法的所有参数中，找到 URL 参数的位置 urlTypeIndex
            if (pts[i].equals(URL.class)) {
                urlTypeIndex = i;
                break;
            }
        }
        return urlTypeIndex;
    }

    /**
     * generate method declaration
     */
    private String generateMethod(Method method) {
        // 方法的返回类型如 void
        String methodReturnType = method.getReturnType().getCanonicalName();
        // 获取方法的名字
        String methodName = method.getName();
        // 方法的类容信息
        String methodContent = generateMethodContent(method);
        // 方法的参数信息
        String methodArgs = generateMethodArguments(method);
        // 方法的异常信息
        String methodThrows = generateMethodThrows(method);
        return String.format(CODE_METHOD_DECLARATION, methodReturnType, methodName, methodArgs, methodThrows, methodContent);
    }

    /**
     * generate method arguments
     */
    private String generateMethodArguments(Method method) {
        Class<?>[] pts = method.getParameterTypes();
        return IntStream.range(0, pts.length)
                .mapToObj(i -> String.format(CODE_METHOD_ARGUMENT, pts[i].getCanonicalName(), i))
                .collect(Collectors.joining(", "));
    }

    /**
     * generate method throws
     */
    private String generateMethodThrows(Method method) {
        Class<?>[] ets = method.getExceptionTypes();
        if (ets.length > 0) {
            String list = Arrays.stream(ets).map(Class::getCanonicalName).collect(Collectors.joining(", "));
            return String.format(CODE_METHOD_THROWS, list);
        } else {
            return "";
        }
    }

    /**
     * generate method URL argument null check
     */
    private String generateUrlNullCheck(int index) {
        return String.format(CODE_URL_NULL_CHECK, index, URL.class.getName(), index);
    }

    /**
     * generate method content
     */
    private String generateMethodContent(Method method) {
        Adaptive adaptiveAnnotation = method.getAnnotation(Adaptive.class);
        StringBuilder code = new StringBuilder(512);
        if (adaptiveAnnotation == null) {
            // 如果方法没有 Adaptive 注解，那么就输出 throw new UnsupportedOperationException("The method %s of interface %s is not adaptive method!");
            return generateUnsupported(method);
        } else {
            // 从方法的所有参数中，找到 URL 参数的位置 urlTypeIndex
            int urlTypeIndex = getUrlTypeIndex(method);

            // found parameter in URL type
            if (urlTypeIndex != -1) {
                // Null Point check
                // if (arg%d == null) throw new IllegalArgumentException("url == null");
                // %s url = arg%d;
                // 也就是对于 url 参数不为空的检查代码
                code.append(generateUrlNullCheck(urlTypeIndex));
            } else {
                // did not find parameter in URL type
                // 尝试从参数的属性中获取 URL 参数，是一种间接的方式
                // if (arg0 == null) throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument == null");
                // if (arg0.getUrl() == null)
                //     throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument getUrl() == null");
                // org.apache.dubbo.common.URL url = arg0.getUrl();
                code.append(generateUrlAssignmentIndirectly(method));
            }

            // 获取方法 Adaptive 注解的 value 值，没有的话就根据 SPI 接口名获取
            String[] value = getMethodAdaptiveValue(adaptiveAnnotation);

            // 查看 method 是否有 org.apache.dubbo.rpc.Invocation 类型的参数
            boolean hasInvocation = hasInvocationArgument(method);

            // 根据 method 的 Invocation 参数生成代码
            // if (arg0 == null) throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument == null");
            // if (arg0.getUrl() == null)
            //     throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument getUrl() == null");
            // org.apache.dubbo.common.URL url = arg0.getUrl();
            code.append(generateInvocationArgumentNullCheck(method));

            // 生成获取 extName 的代码
            // if (arg0 == null) throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument == null");
            // if (arg0.getUrl() == null)
            //     throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument getUrl() == null");
            // org.apache.dubbo.common.URL url = arg0.getUrl();
            // String extName = (url.getProtocol() == null ? "dubbo" : url.getProtocol());
            code.append(generateExtNameAssignment(value, hasInvocation));

            // check extName == null?
            // 生成 extName 不为空检查代码
            // if (arg0 == null) throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument == null");
            // if (arg0.getUrl() == null)
            //     throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument getUrl() == null");
            // org.apache.dubbo.common.URL url = arg0.getUrl();
            // String extName = (url.getProtocol() == null ? "dubbo" : url.getProtocol());
            // if (extName == null)
            //     throw new IllegalStateException("Failed to get extension (org.apache.dubbo.rpc.Protocol) name from url (" + url.toString() + ") use keys([protocol])");
            code.append(generateExtNameNullCheck(value));

            // 生成 extension 获取代码
            // if (arg0 == null) throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument == null");
            // if (arg0.getUrl() == null)
            //     throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument getUrl() == null");
            // org.apache.dubbo.common.URL url = arg0.getUrl();
            // String extName = (url.getProtocol() == null ? "dubbo" : url.getProtocol());
            // if (extName == null)
            //     throw new IllegalStateException("Failed to get extension (org.apache.dubbo.rpc.Protocol) name from url (" + url.toString() + ") use keys([protocol])");
            // org.apache.dubbo.rpc.Protocol extension = (org.apache.dubbo.rpc.Protocol)ExtensionLoader.getExtensionLoader(org.apache.dubbo.rpc.Protocol.class).getExtension(extName);
            code.append(generateExtensionAssignment());

            // return statement
            // if (arg0 == null) throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument == null");
            // if (arg0.getUrl() == null)
            //     throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument getUrl() == null");
            // org.apache.dubbo.common.URL url = arg0.getUrl();
            // String extName = (url.getProtocol() == null ? "dubbo" : url.getProtocol());
            // if (extName == null)
            //     throw new IllegalStateException("Failed to get extension (org.apache.dubbo.rpc.Protocol) name from url (" + url.toString() + ") use keys([protocol])");
            // org.apache.dubbo.rpc.Protocol extension = (org.apache.dubbo.rpc.Protocol) ExtensionLoader.getExtensionLoader(org.apache.dubbo.rpc.Protocol.class).getExtension(extName);
            // return extension.export(arg0);
            code.append(generateReturnAndInvocation(method));
        }

        return code.toString();
    }

    /**
     * generate code for variable extName null check
     * 生成 extName 不为空检查代码
     */
    private String generateExtNameNullCheck(String[] value) {
        // if(extName == null)
        //     throw new IllegalStateException("Failed to get extension (%s) name from url (" + url.toString() + ") use keys(%s)");
        return String.format(CODE_EXT_NAME_NULL_CHECK, type.getName(), Arrays.toString(value));
    }

    /**
     * generate extName assigment code
     * 生成获取 extName 的代码
     * value 为 Adaptive 的 value 值，hasInvocation 为 true，说明 method 有 Invocation 类型参数
     */
    private String generateExtNameAssignment(String[] value, boolean hasInvocation) {
        // TODO: refactor it
        String getNameCode = null;
        for (int i = value.length - 1; i >= 0; --i) {
            if (i == value.length - 1) {
                // 这里的 defaultExtName 通过 new AdaptiveClassCodeGenerator 时传入，来源于 SPI 注解的 value 值
                // 以 Protocol SPI 接口来说，这里就是 dubbo
                // @SPI("dubbo")
                // public interface Protocol
                if (null != defaultExtName) {
                    if (!"protocol".equals(value[i])) {
                        // method 的 Adaptive 注解的 value 为空（此时以 SPI 接口名作为 value 值），且有 Invocation 类型的参数
                        if (hasInvocation) {
                            // 获取 methodName.value[i]，没有的话就使用 defaultExtName
                            getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                        } else {
                            // 直接获取参数
                            getNameCode = String.format("url.getParameter(\"%s\", \"%s\")", value[i], defaultExtName);
                        }
                    } else {
                        // url 协议为空，则使用 defaultExtName，否则使用 url 的协议名
                        getNameCode = String.format("( url.getProtocol() == null ? \"%s\" : url.getProtocol() )", defaultExtName);
                    }
                } else {
                    // 基本跟上个条件一致，需要进行重构
                    if (!"protocol".equals(value[i])) {
                        if (hasInvocation) {
                            getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                        } else {
                            getNameCode = String.format("url.getParameter(\"%s\")", value[i]);
                        }
                    } else {
                        getNameCode = "url.getProtocol()";
                    }
                }
            } else {
                // 和上边的逻辑不是基本一样吗？？
                if (!"protocol".equals(value[i])) {
                    if (hasInvocation) {
                        getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                    } else {
                        getNameCode = String.format("url.getParameter(\"%s\", %s)", value[i], getNameCode);
                    }
                } else {
                    getNameCode = String.format("url.getProtocol() == null ? (%s) : url.getProtocol()", getNameCode);
                }
            }
        }
        // String extName = %s;
        return String.format(CODE_EXT_NAME_ASSIGNMENT, getNameCode);
    }

    /**
     * 生成 extension 获取代码
     *
     * @return
     */
    private String generateExtensionAssignment() {
        // "%s extension = (%<s)%s.getExtensionLoader(%s.class).getExtension(extName);\n";
        return String.format(CODE_EXTENSION_ASSIGNMENT, type.getName(), ExtensionLoader.class.getSimpleName(), type.getName());
    }

    /**
     * generate method invocation statement and return it if necessary
     */
    private String generateReturnAndInvocation(Method method) {
        // 如果方法的返回类型为 void，拼接 return
        String returnStatement = method.getReturnType().equals(void.class) ? "" : "return ";

        // 获取方法的参数，用 "，" 进行分割
        String args = Arrays.stream(method.getParameters()).map(Parameter::getName).collect(Collectors.joining(", "));

        // 拼接方法返回代码，调用 extension 的 method 方法
        return returnStatement + String.format("extension.%s(%s);\n", method.getName(), args);
    }

    /**
     * test if method has argument of type <code>Invocation</code>
     */
    private boolean hasInvocationArgument(Method method) {
        Class<?>[] pts = method.getParameterTypes();
        // 查看 method 是否有 org.apache.dubbo.rpc.Invocation 类型的参数
        return Arrays.stream(pts).anyMatch(p -> CLASSNAME_INVOCATION.equals(p.getName()));
    }

    /**
     * generate code to test argument of type <code>Invocation</code> is null
     */
    private String generateInvocationArgumentNullCheck(Method method) {
        Class<?>[] pts = method.getParameterTypes();
        // 如果 method 某个参数的类型为 org.apache.dubbo.rpc.Invocation，就生成
        // if (arg%d == null) throw new IllegalArgumentException(\"invocation == null\");
        //      String methodName = arg%d.getMethodName();
        return IntStream.range(0, pts.length).filter(i -> CLASSNAME_INVOCATION.equals(pts[i].getName()))
                .mapToObj(i -> String.format(CODE_INVOCATION_ARGUMENT_NULL_CHECK, i, i))
                .findFirst().orElse("");
    }

    /**
     * get value of adaptive annotation or if empty return splitted simple name
     */
    private String[] getMethodAdaptiveValue(Adaptive adaptiveAnnotation) {
        // 尝试从注解中获取 value 值
        String[] value = adaptiveAnnotation.value();
        // value is not set, use the value generated from class name as the key
        if (value.length == 0) {
            // 没有获取到，就根据 SPI 接口名生成
            String splitName = StringUtils.camelToSplitName(type.getSimpleName(), ".");
            value = new String[]{splitName};
        }
        return value;
    }

    /**
     * 尝试从参数的属性中获取 URL 参数，是一种间接的方式
     * get parameter with type <code>URL</code> from method parameter:
     * <p>
     * test if parameter has method which returns type <code>URL</code>
     * <p>
     * if not found, throws IllegalStateException
     */
    private String generateUrlAssignmentIndirectly(Method method) {
        Class<?>[] pts = method.getParameterTypes();

        // find URL getter method
        for (int i = 0; i < pts.length; ++i) {
            for (Method m : pts[i].getMethods()) {
                String name = m.getName();
                // 查找返回 URL 类型的方法，要求 public URL getxxx()
                if ((name.startsWith("get") || name.length() > 3)
                        && Modifier.isPublic(m.getModifiers())
                        && !Modifier.isStatic(m.getModifiers())
                        && m.getParameterTypes().length == 0
                        && m.getReturnType() == URL.class) {
                    // 根据有 URL 属性的参数生成个代码
                    return generateGetUrlNullCheck(i, pts[i], name);
                }
            }
        }

        // getter method not found, throw
        throw new IllegalStateException("Failed to create adaptive class for interface " + type.getName()
                + ": not found url parameter or url attribute in parameters of method " + method.getName());

    }

    /**
     * 根据有 URL 属性的参数生成代码
     * 1, test if argi is null
     * 2, test if argi.getXX() returns null
     * 3, assign url with argi.getXX()
     */
    private String generateGetUrlNullCheck(int index, Class<?> type, String method) {
        // Null point check
        StringBuilder code = new StringBuilder();
        // type 类型的参数必须存在
        code.append(String.format("if (arg%d == null) throw new IllegalArgumentException(\"%s argument == null\");\n",
                index, type.getName()));
        // type 的 getxxx 方法必须返回非空 URL
        code.append(String.format("if (arg%d.%s() == null) throw new IllegalArgumentException(\"%s argument %s() == null\");\n",
                index, method, type.getName(), method));

        // 都满足的话，就对其进行赋值
        code.append(String.format("%s url = arg%d.%s();\n", URL.class.getName(), index, method));
        return code.toString();
    }

}
