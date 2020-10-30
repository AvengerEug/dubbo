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
package org.apache.dubbo.common.bytecode;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class WrapperTest {
    @Test
    public void testMain() throws Exception {
        // 为I1接口生成Wrapper类
        Wrapper w = Wrapper.getWrapper(I1.class);
        // 获取I1当前类的方法
        String[] ns = w.getDeclaredMethodNames();
        // 断言：判断ns的长度是否等于5。不相等则抛异常
        assertEquals(ns.length, 5);
        // 获取I1当前类及其父类的方法(省略Object的方法)  ==> I1 和 I0的所有方法，一共6个
        ns = w.getMethodNames();
        // 断言：判断ns的长度是否为6
        assertEquals(ns.length, 6);

        Object obj = new Impl1();
        // 调用wrapper类的getPropertyValue方法，目的是获取obj的name属性 进而判断值是否为"you name".
        // 这里因为obj是Impl1类，而Impl1类的name属性叫"you name". 因此使用wrapper类获取obj的name属性成功
        assertEquals(w.getPropertyValue(obj, "name"), "you name");

        // 使用wrapper类修改obj对象的name属性为changed
        w.setPropertyValue(obj, "name", "changed");
        // 使用wrapper类获取obj的name属性，判断是否为修改后的值
        assertEquals(w.getPropertyValue(obj, "name"), "changed");

        // 使用wrapper类来调用obj的hello方法，并且传递类型为String类型的参数，且对应参数的名字叫"qianlei"
        w.invokeMethod(obj, "hello", new Class<?>[]{String.class}, new Object[]{"qianlei"});

        System.in.read();
    }

    // bug: DUBBO-132
    @Test
    public void test_unwantedArgument() throws Exception {
        Wrapper w = Wrapper.getWrapper(I1.class);
        Object obj = new Impl1();
        try {
            w.invokeMethod(obj, "hello", new Class<?>[]{String.class, String.class},
                    new Object[]{"qianlei", "badboy"});
            fail();
        } catch (NoSuchMethodException expected) {
        }
    }

    //bug: DUBBO-425
    @Test
    public void test_makeEmptyClass() throws Exception {
        Wrapper.getWrapper(EmptyServiceImpl.class);
    }

    @Test
    public void testHasMethod() throws Exception {
        Wrapper w = Wrapper.getWrapper(I1.class);
        Assertions.assertTrue(w.hasMethod("setName"));
        Assertions.assertTrue(w.hasMethod("hello"));
        Assertions.assertTrue(w.hasMethod("showInt"));
        Assertions.assertTrue(w.hasMethod("getFloat"));
        Assertions.assertTrue(w.hasMethod("setFloat"));
        Assertions.assertFalse(w.hasMethod("setFloatXXX"));
    }

    @Test
    public void testWrapperObject() throws Exception {
        Wrapper w = Wrapper.getWrapper(Object.class);
        Assertions.assertEquals(4, w.getMethodNames().length);
        Assertions.assertEquals(0, w.getPropertyNames().length);
        Assertions.assertNull(w.getPropertyType(null));
    }

    @Test
    public void testGetPropertyValue() throws Exception {
        Assertions.assertThrows(NoSuchPropertyException.class, () -> {
            Wrapper w = Wrapper.getWrapper(Object.class);
            w.getPropertyValue(null, null);
        });
    }

    @Test
    public void testSetPropertyValue() throws Exception {
        Assertions.assertThrows(NoSuchPropertyException.class, () -> {
            Wrapper w = Wrapper.getWrapper(Object.class);
            w.setPropertyValue(null, null, null);
        });
    }

    @Test
    public void testInvokeWrapperObject() throws Exception {
        Wrapper w = Wrapper.getWrapper(Object.class);
        Object instance = new Object();
        Assertions.assertEquals(instance.getClass(), (Class<?>) w.invokeMethod(instance, "getClass", null, null));
        Assertions.assertEquals(instance.hashCode(), (int) w.invokeMethod(instance, "hashCode", null, null));
        Assertions.assertEquals(instance.toString(), (String) w.invokeMethod(instance, "toString", null, null));
        Assertions.assertTrue((boolean)w.invokeMethod(instance, "equals", null, new Object[] {instance}));
    }

    @Test
    public void testNoSuchMethod() throws Exception {
        Assertions.assertThrows(NoSuchMethodException.class, () -> {
            Wrapper w = Wrapper.getWrapper(Object.class);
            w.invokeMethod(new Object(), "__XX__", null, null);
        });
    }

    /**
     * see http://code.alibabatech.com/jira/browse/DUBBO-571
     */
    @Test
    public void test_getDeclaredMethodNames_ContainExtendsParentMethods() throws Exception {
        assertArrayEquals(new String[]{"hello",}, Wrapper.getWrapper(Parent1.class).getMethodNames());

        assertArrayEquals(new String[]{}, Wrapper.getWrapper(Son.class).getDeclaredMethodNames());
    }

    /**
     * see http://code.alibabatech.com/jira/browse/DUBBO-571
     */
    @Test
    public void test_getMethodNames_ContainExtendsParentMethods() throws Exception {
        assertArrayEquals(new String[]{"hello", "world"}, Wrapper.getWrapper(Son.class).getMethodNames());
    }

    public static interface I0 {
        String getName();
    }

    public static interface I1 extends I0 {
        // 认为子类存在名字叫name为属性
        void setName(String name);

        void hello(String name);

        int showInt(int v);

        float getFloat();

        // 认为子类存在名字叫float为属性
        void setFloat(float f);
    }

    public static interface EmptyService {
    }

    public static interface Parent1 {
        void hello();
    }


    public static interface Parent2 {
        void world();
    }

    public static interface Son extends Parent1, Parent2 {

    }

    public static class Impl0 {
        public float a, b, c;
    }

    public static class Impl1 implements I1 {
        private String name = "you name";

        private float fv = 0;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public void hello(String name) {
            System.out.println("hello " + name);
        }

        public int showInt(int v) {
            return v;
        }

        public float getFloat() {
            return fv;
        }

        public void setFloat(float f) {
            fv = f;
        }
    }

    public static class EmptyServiceImpl implements EmptyService {
    }
}