package org.apache.dubbo.common.extension.testextension;


import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.SPI;

/**
 * @Author EvaJohnson
 * @Date 2019-06-11
 * @Email g863821569@gmail.com
 */
@SPI("test")
public interface TestExtension {
//    @Adaptive
//    String methodWithSelectorAnnotation();

    @Adaptive
    String methodWithSelectorAnnotationAndUrlParam(String param1, URL url, String param2);

//    String methodWithoutSelectorAnnotation();
}
