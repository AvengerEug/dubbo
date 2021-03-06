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
package org.apache.dubbo.rpc.proxy.javassist;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.bytecode.Proxy;
import org.apache.dubbo.common.bytecode.Wrapper;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.proxy.AbstractProxyFactory;
import org.apache.dubbo.rpc.proxy.AbstractProxyInvoker;
import org.apache.dubbo.rpc.proxy.InvokerInvocationHandler;

/**
 * JavaassistRpcProxyFactory
 */
public class JavassistProxyFactory extends AbstractProxyFactory {

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getProxy(Invoker<T> invoker, Class<?>[] interfaces) {
        return (T) Proxy.getProxy(interfaces).newInstance(new InvokerInvocationHandler(invoker));
    }

    /**
     * 其主要作用是生成invoker对象，在生成invoker对象之前，会动态生成org.apache.dubbo.common.bytecode.Wrapper类,
     * 而Wrapper类的主要作用是用来包裹目标类，即参数的proxy
     *
     * @param proxy 目标类
     * @param type 目标类的类型
     * @param url 目标类对应的url
     * @param <T> 目标类的类型
     * @return 返回一个invoke对象，其中内部包含一个Wrapper类，而Wrapper类内部包裹了目标类proxy
     */
    @Override
    public <T> Invoker<T> getInvoker(T proxy, Class<T> type, URL url) {
        // TODO Wrapper cannot handle this scenario correctly: the classname contains '$'
        /**
         * 获取目标类的wrapper类 可以使用 arthas工具来查看动态生成的Wrapper类
         * 其中在整个classpath下有两个默认的Wrapper类：
         * 第一个为：org.apache.dubbo.common.bytecode.Wrapper
         * @see org.apache.dubbo.common.bytecode.Wrapper
         * 第二个为：org.apache.dubbo.common.bytecode.Wrapper的内部属性OBJECT_WRAPPER
         * @see Wrapper#OBJECT_WRAPPER
         * 除此之外：会为每一个服务中指定的类型生成一个invoke(包括他们的实现类和接口类)
         */
        final Wrapper wrapper = Wrapper.getWrapper(proxy.getClass().getName().indexOf('$') < 0 ? proxy.getClass() : type);
        // 创建匿名 Invoker 类对象，并实现 doInvoke 方法。
        return new AbstractProxyInvoker<T>(proxy, type, url) {
            @Override
            protected Object doInvoke(T proxy, String methodName,
                                      Class<?>[] parameterTypes,
                                      Object[] arguments) throws Throwable {
                return wrapper.invokeMethod(proxy, methodName, parameterTypes, arguments);
            }
        };
    }

}
