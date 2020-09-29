package com.eugene.sumarry.aop;

import org.apache.dubbo.common.extension.ExtensionLoader;

public class Entry {

    public static void main(String[] args) {
        System.setProperty("dubbo.application.logger", "log4j2");
        ExtensionLoader<UserService> userServiceExtensionLoader = ExtensionLoader.getExtensionLoader(UserService.class);
        UserService userService = userServiceExtensionLoader.getExtension("userService");
        userService.findUsers();
    }
}
