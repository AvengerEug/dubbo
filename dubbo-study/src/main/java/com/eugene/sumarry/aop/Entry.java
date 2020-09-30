package com.eugene.sumarry.aop;

import org.apache.dubbo.common.extension.ExtensionLoader;

public class Entry {

    public static void main(String[] args) {
        System.setProperty("dubbo.application.logger", "log4j2");
        /*
         此行代码执行完成后，会初始化type为ExtensionFactory的ExtensionLoader，其中会扫描classpath下的所有叫
         org.apache.dubbo.common.extension.ExtensionFactory和
         com.alibaba.dubbo.common.extension.ExtensionFactory
         的spi文件，并把相关信息(
         cachedAdaptiveClass(adaptive类)、
         cachedWrapperClasses(wrapper类)、
         cachedActivates(activate类)、
         cachedNames(type的所有实现类)、
         cachedClasses(type的所有实现类)
         中去)存入ExtensionLoader中
         同时，还会初始化AdaptiveExtensionFactory类，这个AdaptiveExtensionFactory类中的list保存的就是所有
         实现了ExtensionFactory的类，默认情况下只有两个：
         SpiExtensionFactory和SpringExtensionFactory
         */
        ExtensionLoader<UserService> userServiceExtensionLoader = ExtensionLoader.getExtensionLoader(UserService.class);


        /**
         * 调用getExtension api时，如果是第一次创建，则会调用getExtensionClasses方法，
         * 最终会进行扫描对应的type的spi文件
         */
        UserService userService = userServiceExtensionLoader.getExtension("userService");
        userService.findUsers();
    }
}
