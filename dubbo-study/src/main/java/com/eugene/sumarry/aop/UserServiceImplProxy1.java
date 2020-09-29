package com.eugene.sumarry.aop;

/**
 * dubbo aop(dubbo的wrapper类)的前提:
 *
 * 在spi文件中加载的实现类中存在一个构造方法，且构造方法的参数为当前接口类型的实现类
 * (被@Adaptive注解标识的不算，因为在dubbo创建aop时是从cachedWrapperClasses属性
 * 中去取的，而只有在加载spi中的实现类时将带有一个带参构造方法，且参数为当前
 * 接口类型的实现类才能被缓存至cachedWrapperClasses属性)
 *
 * dubbo ioc的前提:
 * 1. 接口中要有@SPI注解
 * 2. 在spi文件中添加的实现类中，实现类依赖于当前接口类型的属性，且对这个属性有set方法
 *    (使用dubbo的SpiExtensionLoader来生成代理类的话，那这个set方法名叫什么都没关系，
 *    只要参数类型是接口类型就ok了)
 * 3. 接口中只要有一个方法存在@Adaptive注解
 * 4. 被@Adaptive标识的方法中都要有一个叫URL的属性
 *
 *    其中接口上的@SPI注解中的值或者@Adaptive注解中的值，都会作为一个key,
 *    后续dubbo生成的代理对象中，会通过这个key来获取需要注入的对象，
 *    也就是说，针对ioc功能中，方法一可以注入对象A，方法二可以注入对象B
 *
 *
 */
public class UserServiceImplProxy1 implements UserService {

    private UserService userService;

    public UserServiceImplProxy1(UserService userService) {
        this.userService = userService;
    }

    @Override
    public void findUsers() {
        System.out.println("before 1");
        userService.findUsers();
        System.out.println("after 1");
    }
}
