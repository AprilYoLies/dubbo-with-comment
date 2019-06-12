package org.apache.dubbo.common.extension;

/**
 * @Author EvaJohnson
 * @Date 2019-06-11
 * @Email g863821569@gmail.com
 */

//package org.apache.dubbo.common.extension.testextension.testextension;

import org.apache.dubbo.common.extension.testextension.TestExtension;

public class TestExtension$Adaptive implements TestExtension {
    public java.lang.String methodWithSelectorAnnotationAndUrlParam(java.lang.String arg0, org.apache.dubbo.common.URL arg1, java.lang.String arg2) {
        if (arg1 == null) throw new IllegalArgumentException("url == null");
        org.apache.dubbo.common.URL url = arg1;
        String extName = url.getParameter("test.extension", "test");
        if (extName == null)
            throw new IllegalStateException("Failed to get extension (TestExtension) name from url (" + url.toString() + ") use keys([test.extension])");
        TestExtension extension = (TestExtension) ExtensionLoader.getExtensionLoader(TestExtension.class).getExtension(extName);
        return extension.methodWithSelectorAnnotationAndUrlParam(arg0, arg1, arg2);
    }
}