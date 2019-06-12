package org.apache.dubbo.common.extension.testextension;


import org.apache.dubbo.common.URL;

/**
 * @Author EvaJohnson
 * @Date 2019-06-11
 * @Email g863821569@gmail.com
 */
public class TestExtensionImpl implements TestExtension {
//    @Override
//    public String methodWithSelectorAnnotation() {
//        return "methodWithSelectorAnnotation";
//    }

    @Override
    public String methodWithSelectorAnnotationAndUrlParam(String param1, URL url, String param2) {
        return "methodWithSelectorAnnotationAndUrlParam";
    }

//    @Override
//    public String methodWithoutSelectorAnnotation() {
//        return "methodWithoutSelectorAnnotation";
//    }
}
