/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.dubbo.rpc.proxy;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.support.DemoService;
import org.apache.dubbo.rpc.support.DemoServiceImpl;
import org.apache.dubbo.rpc.support.MyInvoker;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;


public abstract class AbstractProxyTest {

    public static ProxyFactory factory;

    @Test
    public void testGetProxy() throws Exception {
        URL url = URL.valueOf("test://test:11/test?group=dubbo&version=1.1");

        Invoker<DemoService> invoker = new MyInvoker<>(url);

        DemoService proxy = factory.getProxy(invoker);

        Assertions.assertNotNull(proxy);

        Assertions.assertTrue(Arrays.asList(proxy.getClass().getInterfaces()).contains(DemoService.class));

        // Not equal
        //Assertions.assertEquals(proxy.toString(), invoker.toString());
        //Assertions.assertEquals(proxy.hashCode(), invoker.hashCode());

        Assertions.assertEquals(invoker.invoke(new RpcInvocation("echo", new Class[]{String.class}, new Object[]{"aa"})).getValue()
                , proxy.echo("aa"));
    }

    @Test
    public void testGetInvoker() throws Exception {
        URL url = URL.valueOf("test://test:11/test?group=dubbo&version=1.1");

        DemoService origin = new org.apache.dubbo.rpc.support.DemoServiceImpl();

        // 1、new DemoServiceImpl()对象生成Wrapper类，
        // 2、内部再为Wrapper类生成Invoker类，当调用invoker类的invoke方法时，内部的doInvoker方法将委托给Wrapper类处理
        Invoker<DemoService> invoker = factory.getInvoker(new DemoServiceImpl(), DemoService.class, url);

        // 断言：判断invoker内部维护的对象(其实就是上行代码中getInvoker方法的第二个参数)是否是DemoService
        // PS：这里不要被getInterface()这个方法签名给糊弄到了，不是获取它的接口哦！
        Assertions.assertEquals(invoker.getInterface(), DemoService.class);

        // 断言：判断invoker.invoke(new RpcInvocation("echo", new Class[]{String.class}, new Object[]{"aa"})).getValue()的执行结果是否和origin.echo("aa")相等
        // 其中，左侧是调用了invoker的invoke方法，其中参数为自己new出来的RpcInvocation。new出来的RpcInvocation含义为：调用invoker的echo方法，
        //      第一个参数的类型为String，值为aa
        // 右侧则是直接调用DemoServiceImpl的echo方法。
        // 其实最终，左右两侧都是调用了同一个方法，只不过一个是通过invoker来调用的，另外一个是直接调用的
        Assertions.assertEquals(invoker.invoke(new RpcInvocation("echo", new Class[]{String.class}, new Object[]{"aa"})).getValue(),
                origin.echo("aa"));

        System.in.read();
    }

}
