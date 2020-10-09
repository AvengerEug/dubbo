package com.eugene.sumarry.aop;

import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.ExtensionFactory;

/**
 * 若一个接口中有多个实现类存在@Adaptive注解，则会抛异常
 * 因为dubbo不知道该使用哪个作为代理对象，
 * 若只有一个实现类存在@Adaptive注解，则在调用getAdaptiveExtension方法时会将此实现类实例化
 * 若实现类都没有Adaptive注解且接口中的方法没有一个方法有@Adaptive注解的话，在调用getAdaptiveExtension方法时，会抛出异常
 *
 * 所以得出如下结论:
 * 1. @Adaptive 修饰在多个实现类上，在获取代理对象时(即调用getAdaptiveExtension方法)会抛异常
 * 2. @Adaptive 只修饰在实现类上，在获取这个接口的代理对象(即调用getAdaptiveExtension方法)的时候就是这个实现类
 * 3. @Adaptive 修饰在接口中的任意一个方法中，若这个方法中无URL参数，在获取代理对象时会报错
 * 4. @Adaptive 修饰在接口中的任意一个方法中，若这个方法中存在URL参数，则会正常获取代理对象
 */
@Adaptive
public class TestExtensionFactory implements ExtensionFactory {

    @Override
    public <T> T getExtension(Class<T> type, String name) {
        return null;
    }
}
