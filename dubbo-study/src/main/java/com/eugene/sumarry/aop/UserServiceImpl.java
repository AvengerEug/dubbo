package com.eugene.sumarry.aop;

public class UserServiceImpl implements UserService {

    @Override
    public void findUsers() {
        System.out.println("find users");
    }
}
