package com.eugene.sumarry.aop;

import org.apache.dubbo.common.extension.SPI;

@SPI
public interface UserService {

    void findUsers();
}
