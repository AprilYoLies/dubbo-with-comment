package org.apache.dubbo.common.extension;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.compiler.support.JavassistCompiler;
import org.apache.dubbo.common.extension.testextension.TestExtension;
import org.apache.dubbo.common.utils.ClassUtils;
import org.junit.Before;
import org.junit.Test;

/**
 * @Author EvaJohnson
 * @Date 2019-06-11
 * @Email g863821569@gmail.com
 */
public class ExtensionTest {
    ExtensionLoader<?> extensionLoader;

    @Before
    public void testGetExtensionLoader() {
        extensionLoader = ExtensionLoader.getExtensionLoader(TestExtension.class);
    }

    @Test
    public void testGetDefaultExtensionName() {
        String name = extensionLoader.getDefaultExtensionName();
        System.out.println(name);
    }

    @Test
    public void testGetExtensionSelectorInstance() {
        String code = "package org.apache.dubbo.common.extension.testextension;\n" +
                "import org.apache.dubbo.common.extension.ExtensionLoader;\n" +
                "public class TestExtension$Adaptive implements org.apache.dubbo.common.extension.testextension.TestExtension {\n" +
                "public java.lang.String methodWithSelectorAnnotationAndUrlParam(java.lang.String arg0, org.apache.dubbo.common.URL arg1, java.lang.String arg2)  {\n" +
                "if (arg1 == null) throw new IllegalArgumentException(\"url == null\");\n" +
                "org.apache.dubbo.common.URL url = arg1;\n" +
                "String extName = url.getParameter(\"test.extension\", \"test\");\n" +
                "if(extName == null) throw new IllegalStateException(\"Failed to get extension (org.apache.dubbo.common.extension.testextension.TestExtension) name from url (\" + url.toString() + \") use keys([test.extension])\");\n" +
                "org.apache.dubbo.common.extension.testextension.TestExtension extension = (org.apache.dubbo.common.extension.testextension.TestExtension)ExtensionLoader.getExtensionLoader(org.apache.dubbo.common.extension.testextension.TestExtension.class).getExtension(extName);\n" +
                "return extension.methodWithSelectorAnnotationAndUrlParam(arg0, arg1, arg2);\n" +
                "}\n" +
                "}";
        ClassLoader classLoader = ClassUtils.getClassLoader(ExtensionTest.class);
        JavassistCompiler compiler = new JavassistCompiler();
        Class<?>[] interfaces1 = compiler.compile(code, classLoader).getInterfaces();

        Object obj = extensionLoader.getAdaptiveExtension();
        Class<?>[] interfaces = obj.getClass().getInterfaces();
        TestExtension instance = (TestExtension) obj;
        System.out.println(instance.methodWithSelectorAnnotationAndUrlParam("param1", URL.valueOf("dubbo://127.0.0.1:2287"), "param3"));
    }
}
