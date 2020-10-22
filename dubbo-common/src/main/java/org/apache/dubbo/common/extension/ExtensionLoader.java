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
package org.apache.dubbo.common.extension;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.factory.AdaptiveExtensionFactory;
import org.apache.dubbo.common.extension.factory.SpiExtensionFactory;
import org.apache.dubbo.common.extension.support.ActivateComparator;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ArrayUtils;
import org.apache.dubbo.common.utils.ClassUtils;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.Holder;
import org.apache.dubbo.common.utils.ReflectUtils;
import org.apache.dubbo.common.utils.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SPLIT_PATTERN;
import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.REMOVE_VALUE_PREFIX;

/**
 * Load dubbo extensions
 * <ul>
 * <li>auto inject dependency extension </li>
 * <li>auto wrap extension in wrapper </li>
 * <li>default extension is an adaptive instance</li>
 * </ul>
 *
 * @see <a href="http://java.sun.com/j2se/1.5.0/docs/guide/jar/jar.html#Service%20Provider">Service Provider in Java 5</a>
 * @see org.apache.dubbo.common.extension.SPI
 * @see org.apache.dubbo.common.extension.Adaptive
 * @see org.apache.dubbo.common.extension.Activate
 */
public class ExtensionLoader<T> {

    private static final Logger logger = LoggerFactory.getLogger(ExtensionLoader.class);

    /**
     * 此三个属性是在扫描spi文件时的三个基础目录
     */
    private static final String SERVICES_DIRECTORY = "META-INF/services/";
    private static final String DUBBO_DIRECTORY = "META-INF/dubbo/";
    private static final String DUBBO_INTERNAL_DIRECTORY = DUBBO_DIRECTORY + "internal/";

    /**
     * 以逗号分隔的正则
     */
    private static final Pattern NAME_SEPARATOR = Pattern.compile("\\s*[,]+\\s*");

    /**
     * 存放所有的ExtensionLoader，static修饰的，在JVM中放在方法区，所有的ExtensionLoader共享
     */
    private static final ConcurrentMap<Class<?>, ExtensionLoader<?>> EXTENSION_LOADERS = new ConcurrentHashMap<>();

    /**
     * 存放SPI中配置的所有实例，所有的ExtensionLoader共享
     */
    private static final ConcurrentMap<Class<?>, Object> EXTENSION_INSTANCES = new ConcurrentHashMap<>();

    // ==============================

    /**
     * 当前ExtensionLoader维护的type，一个@SPI修饰的接口，对应一个ExtensionLoader
     */
    private final Class<?> type;

    /**
     * 如果当前ExtensionLoader中维护的type不是ExtensionFactory，此时这个objectFactory属性中
     * 存的值就是AdaptiveExtensionFactory类了
     * @see AdaptiveExtensionFactory
     *
     * AdaptiveExtensionFactory类是在创建type为ExtensionFactory的ExtensionLoader时把它给实例化的，
     * 然后放在了type为ExtensionFactory的ExtensionLoader的cachedAdaptiveInstance属性中
     */
    private final ExtensionFactory objectFactory;

    /**
     * 在解析SPI文件时，缓存的名字，基本上就是SPI文件中配置的东西
     * key为class，value为name
     */
    private final ConcurrentMap<Class<?>, String> cachedNames = new ConcurrentHashMap<>();

    /**
     * 使用包装器设计模式，定义了一个抽象的holder(可以把它理解成一个口袋，里面可以存放任意的东西)
     * 在此处，泛型为map，其中map里面存储的是SPI文件中配置的东西
     * 与cachedNames相反，这里的key为name，value为class
     */
    private final Holder<Map<String, Class<?>>> cachedClasses = new Holder<>();

    /**
     * 缓存spi实现类中被@Activate注解标识的实现类
     * @see Activate
     */
    private final Map<String, Object> cachedActivates = new ConcurrentHashMap<>();

    /**
     * 缓存spi的所有实现类，其中key为spi中配置的name，value为对应的实例
     */
    private final ConcurrentMap<String, Holder<Object>> cachedInstances = new ConcurrentHashMap<>();

    /**
     * 缓存当前实现ExtensionLoader对应的adaptive类，只有调用了
     * @org.apache.dubbo.common.extension.ExtensionLoader#getAdaptiveExtension()
     * 方法时，才会缓存至此属性
     */
    private final Holder<Object> cachedAdaptiveInstance = new Holder<>();

    /**
     * 缓存adaptive的class类(前提：是会构建扩展类),
     * 若指定Type的spi中配置的实现类中有被@Adaptive注解标识，则使用它，
     * 否则Dubbo将会动态生成自适应扩展类
     */
    private volatile Class<?> cachedAdaptiveClass = null;

    /**
     * 缓存@SPI注解的值，若有多个，则取第一个
     */
    private String cachedDefaultName;

    /**
     * 保存创建adaptive代理类的错误信息
     */
    private volatile Throwable createAdaptiveInstanceError;

    /**
     * 缓存所有的wrapper类信息
     */
    private Set<Class<?>> cachedWrapperClasses;

    private Map<String, IllegalStateException> exceptions = new ConcurrentHashMap<>();

    /**
     * 私有构造方法，此构造方法比较重要，基本上都是在此步骤对当前ExtensionLoader对应的SPI文件进行解析，
     * 大致总结下构造方法做的事情：
     *
     * 1、绑定当前ExtensionLoader负责的Type
     * 2、为当前ExtensionLoader填充objectFactory属性
     *    此属性比较特殊，要视情况而定。
     *    2.1、如果当前的ExtensionLoader对应的Type是org.apache.dubbo.common.extension.ExtensionFactory，
     *         那么它的objectFactory就是null。
     *
     *    2.2、如果当前的ExtensionLoader对应的Type是非org.apache.dubbo.common.extension.ExtensionFactory类，
     *         那么它的objectFactory就是ExtensionFactory的扩展类。而对于ExtensionFactory类而言，dubbo框架
     *         在它的实现类中，org.apache.dubbo.common.extension.factory.AdaptiveExtensionFactory类被
     *          @Adaptive注解修饰了，因此dubbo框架不会去生成代理类，而是直接选择AdaptiveExtensionFactory
     *          作为代理类
     */
    private ExtensionLoader(Class<?> type) {
        this.type = type;
        objectFactory = (type == ExtensionFactory.class ? null : ExtensionLoader.getExtensionLoader(ExtensionFactory.class).getAdaptiveExtension());
    }

    private static <T> boolean withExtensionAnnotation(Class<T> type) {
        return type.isAnnotationPresent(SPI.class);
    }

    @SuppressWarnings("unchecked")
    public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        if (type == null) {
            throw new IllegalArgumentException("Extension type == null");
        }
        if (!type.isInterface()) {
            throw new IllegalArgumentException("Extension type (" + type + ") is not an interface!");
        }
        if (!withExtensionAnnotation(type)) {
            throw new IllegalArgumentException("Extension type (" + type +
                    ") is not an extension, because it is NOT annotated with @" + SPI.class.getSimpleName() + "!");
        }

        ExtensionLoader<T> loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        if (loader == null) {
            EXTENSION_LOADERS.putIfAbsent(type, new ExtensionLoader<T>(type));
            loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        }
        return loader;
    }

    // For testing purposes only
    public static void resetExtensionLoader(Class type) {
        ExtensionLoader loader = EXTENSION_LOADERS.get(type);
        if (loader != null) {
            // Remove all instances associated with this loader as well
            Map<String, Class<?>> classes = loader.getExtensionClasses();
            for (Map.Entry<String, Class<?>> entry : classes.entrySet()) {
                EXTENSION_INSTANCES.remove(entry.getValue());
            }
            classes.clear();
            EXTENSION_LOADERS.remove(type);
        }
    }

    private static ClassLoader findClassLoader() {
        return ClassUtils.getClassLoader(ExtensionLoader.class);
    }

    public String getExtensionName(T extensionInstance) {
        return getExtensionName(extensionInstance.getClass());
    }

    public String getExtensionName(Class<?> extensionClass) {
        getExtensionClasses();// load class
        return cachedNames.get(extensionClass);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, key, null)}
     *
     * @param url url
     * @param key url parameter key which used to get extension point names
     * @return extension list which are activated.
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String, String)
     */
    public List<T> getActivateExtension(URL url, String key) {
        return getActivateExtension(url, key, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, values, null)}
     *
     * @param url    url
     * @param values extension point names
     * @return extension list which are activated
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String[] values) {
        return getActivateExtension(url, values, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, url.getParameter(key).split(","), null)}
     *
     * @param url   url
     * @param key   url parameter key which used to get extension point names
     * @param group group
     * @return extension list which are activated.
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String key, String group) {
        String value = url.getParameter(key);
        return getActivateExtension(url, StringUtils.isEmpty(value) ? null : COMMA_SPLIT_PATTERN.split(value), group);
    }

    /**
     * Get activate extensions.
     *
     * @param url    url
     * @param values extension point names
     * @param group  group
     * @return extension list which are activated
     * @see org.apache.dubbo.common.extension.Activate
     */
    public List<T> getActivateExtension(URL url, String[] values, String group) {
        List<T> exts = new ArrayList<>();
        List<String> names = values == null ? new ArrayList<>(0) : Arrays.asList(values);
        if (!names.contains(REMOVE_VALUE_PREFIX + DEFAULT_KEY)) {
            getExtensionClasses();
            for (Map.Entry<String, Object> entry : cachedActivates.entrySet()) {
                String name = entry.getKey();
                Object activate = entry.getValue();

                String[] activateGroup, activateValue;

                if (activate instanceof Activate) {
                    activateGroup = ((Activate) activate).group();
                    activateValue = ((Activate) activate).value();
                } else if (activate instanceof com.alibaba.dubbo.common.extension.Activate) {
                    activateGroup = ((com.alibaba.dubbo.common.extension.Activate) activate).group();
                    activateValue = ((com.alibaba.dubbo.common.extension.Activate) activate).value();
                } else {
                    continue;
                }
                if (isMatchGroup(group, activateGroup)) {
                    T ext = getExtension(name);
                    if (!names.contains(name)
                            && !names.contains(REMOVE_VALUE_PREFIX + name)
                            && isActive(activateValue, url)) {
                        exts.add(ext);
                    }
                }
            }
            exts.sort(ActivateComparator.COMPARATOR);
        }
        List<T> usrs = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            if (!name.startsWith(REMOVE_VALUE_PREFIX)
                    && !names.contains(REMOVE_VALUE_PREFIX + name)) {
                if (DEFAULT_KEY.equals(name)) {
                    if (!usrs.isEmpty()) {
                        exts.addAll(0, usrs);
                        usrs.clear();
                    }
                } else {
                    T ext = getExtension(name);
                    usrs.add(ext);
                }
            }
        }
        if (!usrs.isEmpty()) {
            exts.addAll(usrs);
        }
        return exts;
    }

    private boolean isMatchGroup(String group, String[] groups) {
        if (StringUtils.isEmpty(group)) {
            return true;
        }
        if (groups != null && groups.length > 0) {
            for (String g : groups) {
                if (group.equals(g)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isActive(String[] keys, URL url) {
        if (keys.length == 0) {
            return true;
        }
        for (String key : keys) {
            for (Map.Entry<String, String> entry : url.getParameters().entrySet()) {
                String k = entry.getKey();
                String v = entry.getValue();
                if ((k.equals(key) || k.endsWith("." + key))
                        && ConfigUtils.isNotEmpty(v)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Get extension's instance. Return <code>null</code> if extension is not found or is not initialized. Pls. note
     * that this method will not trigger extension load.
     * <p>
     * In order to trigger extension load, call {@link #getExtension(String)} instead.
     *
     * @see #getExtension(String)
     */
    @SuppressWarnings("unchecked")
    public T getLoadedExtension(String name) {
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        Holder<Object> holder = getOrCreateHolder(name);
        return (T) holder.get();
    }

    private Holder<Object> getOrCreateHolder(String name) {
        Holder<Object> holder = cachedInstances.get(name);
        if (holder == null) {
            cachedInstances.putIfAbsent(name, new Holder<>());
            holder = cachedInstances.get(name);
        }
        return holder;
    }

    /**
     * Return the list of extensions which are already loaded.
     * <p>
     * Usually {@link #getSupportedExtensions()} should be called in order to get all extensions.
     *
     * @see #getSupportedExtensions()
     */
    public Set<String> getLoadedExtensions() {
        return Collections.unmodifiableSet(new TreeSet<>(cachedInstances.keySet()));
    }

    public Object getLoadedAdaptiveExtensionInstances() {
        return cachedAdaptiveInstance.get();
    }

    /**
     * Find the extension with the given name. If the specified name is not found, then {@link IllegalStateException}
     * will be thrown.
     */
    @SuppressWarnings("unchecked")
    public T getExtension(String name) {
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        if ("true".equals(name)) {
            return getDefaultExtension();
        }
        final Holder<Object> holder = getOrCreateHolder(name);
        Object instance = holder.get();
        if (instance == null) {
            synchronized (holder) {
                instance = holder.get();
                if (instance == null) {
                    instance = createExtension(name);
                    holder.set(instance);
                }
            }
        }
        return (T) instance;
    }

    /**
     * Return default extension, return <code>null</code> if it's not configured.
     */
    public T getDefaultExtension() {
        getExtensionClasses();
        if (StringUtils.isBlank(cachedDefaultName) || "true".equals(cachedDefaultName)) {
            return null;
        }
        return getExtension(cachedDefaultName);
    }

    public boolean hasExtension(String name) {
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        Class<?> c = this.getExtensionClass(name);
        return c != null;
    }

    public Set<String> getSupportedExtensions() {
        Map<String, Class<?>> clazzes = getExtensionClasses();
        return Collections.unmodifiableSet(new TreeSet<>(clazzes.keySet()));
    }

    /**
     * Return default extension name, return <code>null</code> if not configured.
     */
    public String getDefaultExtensionName() {
        getExtensionClasses();
        return cachedDefaultName;
    }

    /**
     * Register new extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension with the same name has already been registered.
     */
    public void addExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + " doesn't implement the Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + " can't be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " already exists (Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
        } else {
            if (cachedAdaptiveClass != null) {
                throw new IllegalStateException("Adaptive Extension already exists (Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
        }
    }

    /**
     * Replace the existing extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension to be placed doesn't exist
     * @deprecated not recommended any longer, and use only when test
     */
    @Deprecated
    public void replaceExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + " doesn't implement Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + " can't be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (!cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " doesn't exist (Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
            cachedInstances.remove(name);
        } else {
            if (cachedAdaptiveClass == null) {
                throw new IllegalStateException("Adaptive Extension doesn't exist (Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
            cachedAdaptiveInstance.set(null);
        }
    }

    /**
     * 第一次进入这个方法时，当前的this为ExtensionFactory的扩展类，因此此时目的是获取ExtensionFactory类型的扩展
     * 即加载classpath下面的所有叫org.apache.dubbo.common.extension.ExtensionFactory的文件
     * @see ExtensionLoader#createAdaptiveExtension()
     * 的这段代码：
     * ExtensionLoader.getExtensionLoader(ExtensionFactory.class).getAdaptiveExtension()
     *
     * 如果当前的ExtensionLoader对应的Type的实现类中，不存在一个被@Adaptive注解标识的类的话，此时dubbo框架会动态的
     * 生成一个adaptive类，目的就是为了在方法中能够根据传入的参数来决定使用具体的实现类。其中，这个代理类的逻辑大致为如下：
     * 以官方的车轮制造厂接口 WheelMaker为例：
     * public interface WheelMaker {
     *    Wheel makeWheel(URL url);
     * }
     *
     * 根据此接口，生成的adaptive代理类为(能根据传入的参数来决定使用哪一个WheelMaker)：
     * public class AdaptiveWheelMaker implements WheelMaker {
     *     public Wheel makeWheel(URL url) {
     *         if (url == null) {
     *             throw new IllegalArgumentException("url == null");
     *         }
     *
     *     // 1.从 URL 中获取 WheelMaker 名称
     *     String wheelMakerName = url.getParameter("Wheel.maker");
     *     if (wheelMakerName == null) {
     *         throw new IllegalArgumentException("wheelMakerName == null");
     *     }
     *
     *     // 2.通过 SPI 加载具体的 WheelMaker
     *     WheelMaker wheelMaker = ExtensionLoader.getExtensionLoader(WheelMaker.class).getExtension(wheelMakerName);
     *
     *     // 3.调用目标方法
     *     return wheelMaker.makeWheel(url);
     *     }
     * }
     *
     * 如果当前的ExtensionLoader对应的Type的实现类中，存在一个(如果有多个则抛异常)被@Adaptive注解标识的类的话，此时就会将此类实例化，
     * 并赋值给objectFactory属性，最具有代表意义的就是type为org.apache.dubbo.common.extension.ExtensionFactory的
     * ExtensionLoader，它内部维护的objectFactory就是org.apache.dubbo.common.extension.factory.AdaptiveExtensionFactory
     *
     */
    @SuppressWarnings("unchecked")
    public T getAdaptiveExtension() {

        Object instance = cachedAdaptiveInstance.get();
        if (instance == null) {
            if (createAdaptiveInstanceError == null) {
                synchronized (cachedAdaptiveInstance) {
                    instance = cachedAdaptiveInstance.get();
                    if (instance == null) {
                        try {
                            instance = createAdaptiveExtension();
                            cachedAdaptiveInstance.set(instance);
                        } catch (Throwable t) {
                            createAdaptiveInstanceError = t;
                            throw new IllegalStateException("Failed to create adaptive instance: " + t.toString(), t);
                        }
                    }
                }
            } else {
                throw new IllegalStateException("Failed to create adaptive instance: " + createAdaptiveInstanceError.toString(), createAdaptiveInstanceError);
            }
        }

        return (T) instance;
    }

    private IllegalStateException findException(String name) {
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (entry.getKey().toLowerCase().contains(name.toLowerCase())) {
                return entry.getValue();
            }
        }
        StringBuilder buf = new StringBuilder("No such extension " + type.getName() + " by name " + name);


        int i = 1;
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (i == 1) {
                buf.append(", possible causes: ");
            }

            buf.append("\r\n(");
            buf.append(i++);
            buf.append(") ");
            buf.append(entry.getKey());
            buf.append(":\r\n");
            buf.append(StringUtils.toString(entry.getValue()));
        }
        return new IllegalStateException(buf.toString());
    }

    /**
     * 开始创建在spi中的实例，主要分为如下几个步骤：
     * 1、加载对应type的spi文件
     * 2、如果给定的name与spi中配置的name不匹配，则直接抛出异常 ==> 说明针对当前type的ExtensionLoader中并没有在扫描出来的SPI
     *    文件中发现相同的name
     * 3、先从缓存EXTENSION_INSTANCES(jux下的map)中去获取，不存在则创建，存在则直接使用
     * 4、注入实例，
     *     注入实例包含两种:
     *     一种是IOC的功能，另外一种是AOP的功能(Wrapper类)
     *
     *    这里有个注意点：就是不管这个实例是从缓存中获取的还是新建的都会再执行
     *    注入实例的方法，同理，如果当前的实例类型为ExtensionFactory那么肯定不需要注入了，
     *    因为它对应的ExtensionLoader的objectFactory属性为空，不需要注入。
     *
     *
     * @param name
     * @return
     */
    @SuppressWarnings("unchecked")
    private T createExtension(String name) {
        Class<?> clazz = getExtensionClasses().get(name);
        if (clazz == null) {
            throw findException(name);
        }
        try {
            T instance = (T) EXTENSION_INSTANCES.get(clazz);
            if (instance == null) {
                EXTENSION_INSTANCES.putIfAbsent(clazz, clazz.newInstance());
                instance = (T) EXTENSION_INSTANCES.get(clazz);
            }
            injectExtension(instance);
            Set<Class<?>> wrapperClasses = cachedWrapperClasses;
            // 获取所有的wrapper类，并挨个调用他们的构造方法实例化wrapper类对象，并挨个进行依赖注入
            if (CollectionUtils.isNotEmpty(wrapperClasses)) {
                for (Class<?> wrapperClass : wrapperClasses) {
                    instance = injectExtension((T) wrapperClass.getConstructor(type).newInstance(instance));
                }
            }
            return instance;
        } catch (Throwable t) {
            throw new IllegalStateException("Extension instance (name: " + name + ", class: " +
                    type + ") couldn't be instantiated: " + t.getMessage(), t);
        }
    }

    /**
     * 开始注入扩展类，有几个硬性条件：
     *   1、只有当前ExtensionLoader中objectFactory属性有值才行，objectFactory属性一般是在构造方法中给填充进去的。
     *      当ExtensionLoader维护的type为ExtensionFactory时，也就是要获取类型为ExtensionFactory的
     *      extensionLoader时，它的objectFactory对象为null，因此不需要注入
     *   2、当前实例有set方法，且不能为私有、不能存在@DisableInject注解
     *
     * 此方法其实就是IOC功能
     *
     * @param instance
     * @return
     */
    private T injectExtension(T instance) {
        try {
            if (objectFactory != null) {
                for (Method method : instance.getClass().getMethods()) {
                    // 检测方法是否以 set 开头，且方法仅有一个参数，且方法访问级别为 public
                    if (isSetter(method)) {
                        /**
                         * Check {@link DisableInject} to see if we need auto injection for this property
                         */
                        if (method.getAnnotation(DisableInject.class) != null) {
                            continue;
                        }
                        Class<?> pt = method.getParameterTypes()[0];
                        if (ReflectUtils.isPrimitives(pt)) {
                            continue;
                        }
                        try {
                            // 获取方法后面的签名，eg: 方法名为：setVersion, 调用此方法后，将返回version
                            String property = getSetterProperty(method);
                            /**
                             * 注入进去的对象主要由如下代码决定，其中objectFactory的属性为adaptiveExtensionFactory
                             * 其中内部维护了两个ExtensionFactory，分别为：
                             * @see SpiExtensionFactory  -> 普通情况下，使用的是此ExtensionFactory，
                             *      且调用getExtesion方法时，返回的被注入属性类型的adaptiveExtension(即自适应扩展类)，此种情况下，属性名没什么作用
                             * @see org.apache.dubbo.config.spring.extension.SpringExtensionFactory  -> 用于从 Spring 的 IOC 容器中获取所需的拓展
                             */
                            Object object = objectFactory.getExtension(pt, property);
                            if (object != null) {
                                method.invoke(instance, object);
                            }
                        } catch (Exception e) {
                            logger.error("Failed to inject via method " + method.getName()
                                    + " of interface " + type.getName() + ": " + e.getMessage(), e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return instance;
    }

    /**
     * get properties name for setter, for instance: setVersion, return "version"
     * <p>
     * return "", if setter name with length less than 3
     */
    private String getSetterProperty(Method method) {
        return method.getName().length() > 3 ? method.getName().substring(3, 4).toLowerCase() + method.getName().substring(4) : "";
    }

    /**
     * return true if and only if:
     * <p>
     * 1, public
     * <p>
     * 2, name starts with "set"
     * <p>
     * 3, only has one parameter
     */
    private boolean isSetter(Method method) {
        return method.getName().startsWith("set")
                && method.getParameterTypes().length == 1
                && Modifier.isPublic(method.getModifiers());
    }

    private Class<?> getExtensionClass(String name) {
        if (type == null) {
            throw new IllegalArgumentException("Extension type == null");
        }
        if (name == null) {
            throw new IllegalArgumentException("Extension name == null");
        }
        return getExtensionClasses().get(name);
    }

    /**
     * 官方给的注释：synchronized in getExtensionClasses
     *
     * 加载SPI的class类
     * 第一步：缓存默认的扩展名称，保存至cachedDefaultName中，默认为null，如果type对应的类中的@SPI接口中有value，则使用指定的value
     * @see ExtensionLoader#cacheDefaultExtensionName()
     *
     * 第二步：记载classpath下面的三个指定目录下的type文件
     * 六个指定目录：
     * META-INF/dubbo/internal/
     * META-INF/dubbo/
     * META-INF/services/
     *
     * (type是一个Class对象，eg: com.eugene.sumarry.aop.UserService, 那么就是去六个指定目录下加载com.eugene.sumarry.aop.UserService文件)
     * 同时，如果type是框架内部的特殊名称前缀，eg: org.apache，此时会将org.apache替换成com.alibaba，并同时加载
     * org.apache和com.alibaba的spi文件
     * @see ExtensionLoader#loadDirectory(java.util.Map, java.lang.String, java.lang.String)
     * 记载spi文件的细节主要参考如下方法
     * @see ExtensionLoader#loadClass(java.util.Map, java.net.URL, java.lang.Class, java.lang.String)
     *
     * @return 返回一个map，其中key为spi文件中配置的key，value为spi配置的value，只不过value转成了Class对象
     * 总结：因此，执行完这个方法后，对当前spi文件中配置的所有类都会保存到当前ExtensionLoad对应的
     *      cachedAdaptiveClass、cachedWrapperClasses、cachedActivates、cachedNames、cachedClasses中去，
     */
    private Map<String, Class<?>> loadExtensionClasses() {
        cacheDefaultExtensionName();

        Map<String, Class<?>> extensionClasses = new HashMap<>();
        loadDirectory(extensionClasses, DUBBO_INTERNAL_DIRECTORY, type.getName());
        loadDirectory(extensionClasses, DUBBO_INTERNAL_DIRECTORY, type.getName().replace("org.apache", "com.alibaba"));
        loadDirectory(extensionClasses, DUBBO_DIRECTORY, type.getName());
        loadDirectory(extensionClasses, DUBBO_DIRECTORY, type.getName().replace("org.apache", "com.alibaba"));
        loadDirectory(extensionClasses, SERVICES_DIRECTORY, type.getName());
        loadDirectory(extensionClasses, SERVICES_DIRECTORY, type.getName().replace("org.apache", "com.alibaba"));
        return extensionClasses;
    }

    /**
     * 先从缓存中获取，缓存不存在则直接加载SPI文件(只加载当前类相关的SPI文件)，
     * 打个比方：如果当前类(ExtensionLoader)type属性是 com.eugene.sumarry.aop.UserService
     * 那么此时加载的就是名字为com.eugene.sumarry.aop.UserService的spi文件
     * @see  ExtensionLoader#loadExtensionClasses()
     *
     * 执行完loadExtensionClasses后，会把加载出来的一些Class对象(所有实现type属性对应的类的对象)
     * 添加到Map中(其中key为spi文件中配置的key，value为spi对应的value)，并将它缓存起来。
     *
     * 此方法是加载spi文件的路口，通常是通过
     * getExtension和getAdaptiveExtension这两个api来触发此方法进而完成对spi的加载的。
     *
     * @return 返回的是一个map，其中key为spi中配置的name，value为spi中配置的value，同时当前ExtensionLoader中的cachedClasses属性中有值了
     */
    private Map<String, Class<?>> getExtensionClasses() {
        Map<String, Class<?>> classes = cachedClasses.get();
        if (classes == null) {
            synchronized (cachedClasses) {
                classes = cachedClasses.get();
                if (classes == null) {
                    classes = loadExtensionClasses();
                    cachedClasses.set(classes);
                }
            }
        }
        return classes;
    }

    /**
     * extract and cache default extension name if exists
     */
    private void cacheDefaultExtensionName() {
        final SPI defaultAnnotation = type.getAnnotation(SPI.class);
        if (defaultAnnotation != null) {
            String value = defaultAnnotation.value();
            if ((value = value.trim()).length() > 0) {
                String[] names = NAME_SEPARATOR.split(value);
                if (names.length > 1) {
                    throw new IllegalStateException("More than 1 default extension name on extension " + type.getName()
                            + ": " + Arrays.toString(names));
                }
                if (names.length == 1) {
                    cachedDefaultName = names[0];
                }
            }
        }
    }

    private void loadDirectory(Map<String, Class<?>> extensionClasses, String dir, String type) {
        String fileName = dir + type;
        try {
            Enumeration<java.net.URL> urls;
            ClassLoader classLoader = findClassLoader();
            if (classLoader != null) {
                urls = classLoader.getResources(fileName);
            } else {
                urls = ClassLoader.getSystemResources(fileName);
            }
            if (urls != null) {
                while (urls.hasMoreElements()) {
                    java.net.URL resourceURL = urls.nextElement();
                    loadResource(extensionClasses, classLoader, resourceURL);
                }
            }
        } catch (Throwable t) {
            logger.error("Exception occurred when loading extension class (interface: " +
                    type + ", description file: " + fileName + ").", t);
        }
    }

    private void loadResource(Map<String, Class<?>> extensionClasses, ClassLoader classLoader, java.net.URL resourceURL) {
        try {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(resourceURL.openStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    final int ci = line.indexOf('#');
                    if (ci >= 0) {
                        line = line.substring(0, ci);
                    }
                    line = line.trim();
                    if (line.length() > 0) {
                        try {
                            String name = null;
                            int i = line.indexOf('=');
                            if (i > 0) {
                                name = line.substring(0, i).trim();
                                line = line.substring(i + 1).trim();
                            }
                            if (line.length() > 0) {
                                loadClass(extensionClasses, resourceURL, Class.forName(line, true, classLoader), name);
                            }
                        } catch (Throwable t) {
                            IllegalStateException e = new IllegalStateException("Failed to load extension class (interface: " + type + ", class line: " + line + ") in " + resourceURL + ", cause: " + t.getMessage(), t);
                            exceptions.put(line, e);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            logger.error("Exception occurred when loading extension class (interface: " +
                    type + ", class file: " + resourceURL + ") in " + resourceURL, t);
        }
    }

    /**
     * 此方法做了蛮多事
     * 1、spi中配置的实现类中存在@Adaptive注解，则将它缓存至ExtensionLoad的cachedAdaptiveClass属性中
     *    这个cachedAdaptiveClass属性为一个Class对象，只能缓存一个被@Adaptive注解修饰的实现类，若有
     *    多个，则直接抛异常。具体见如下方法的实现：
     * @see ExtensionLoader#cacheAdaptiveClass(java.lang.Class)
     *
     * 2、spi中配置的实现类是一个wrapper类，则将它缓存到cachedWrapperClasses中，这个属性为juc下面的map，
     *    因此，可以存多个wrapper类。其中判断一个实现类是否为wrapper类的步骤参考如下方法.
     *    (大致的逻辑就是看这个类是否有以Type为参数的有参构造方法)
     * @see ExtensionLoader#isWrapperClass(java.lang.Class)
     *
     * 3、spi中配置的实现类是一个被@Activate注解修饰的类，会被缓存到cachedActivates的map中去
     *
     * 4、将当前spi中配置的名称缓存到当前ExtensionLoader对应的cachedNames中，其中cachedNames是一个juc下面的map，
     *   key为当前spi文件中配置的value(会转换成Class对象)，value为spi中配置的名称, 与cachedClasses属性缓存的相反，
     *   cachedClasses内部的data是一个map，其中key为spi中配置的名称，value为spi中配置的value
     *
     * 5、将解析spi出来的类全部存到传入的extensionClasses参数中，最终会存到当前ExtensionLoader的cachedClasses属性中
     *
     * @param extensionClasses 存储解析出来的spi文件的map，最终会存到当前ExtensionLoader的cachedClasses属性中
     * @param resourceURL 对应当前解析的spi文件
     * @param clazz 在spi中配置的value，并把它转换成Class对象了
     * @param name 在spi中配置的key
     * @throws NoSuchMethodException
     */
    private void loadClass(Map<String, Class<?>> extensionClasses, java.net.URL resourceURL, Class<?> clazz, String name) throws NoSuchMethodException {
        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Error occurred when loading extension class (interface: " +
                    type + ", class line: " + clazz.getName() + "), class "
                    + clazz.getName() + " is not subtype of interface.");
        }
        if (clazz.isAnnotationPresent(Adaptive.class)) {
            cacheAdaptiveClass(clazz);
        } else if (isWrapperClass(clazz)) {
            cacheWrapperClass(clazz);
        } else {
            clazz.getConstructor();
            if (StringUtils.isEmpty(name)) {
                name = findAnnotationName(clazz);
                if (name.length() == 0) {
                    throw new IllegalStateException("No such extension name for the class " + clazz.getName() + " in the config " + resourceURL);
                }
            }

            String[] names = NAME_SEPARATOR.split(name);
            if (ArrayUtils.isNotEmpty(names)) {
                cacheActivateClass(clazz, names[0]);
                for (String n : names) {
                    cacheName(clazz, n);
                    saveInExtensionClass(extensionClasses, clazz, n);
                }
            }
        }
    }

    /**
     * cache name
     */
    private void cacheName(Class<?> clazz, String name) {
        if (!cachedNames.containsKey(clazz)) {
            cachedNames.put(clazz, name);
        }
    }

    /**
     * put clazz in extensionClasses
     */
    private void saveInExtensionClass(Map<String, Class<?>> extensionClasses, Class<?> clazz, String name) {
        Class<?> c = extensionClasses.get(name);
        if (c == null) {
            extensionClasses.put(name, clazz);
        } else if (c != clazz) {
            throw new IllegalStateException("Duplicate extension " + type.getName() + " name " + name + " on " + c.getName() + " and " + clazz.getName());
        }
    }

    /**
     * cache Activate class which is annotated with <code>Activate</code>
     * <p>
     * for compatibility, also cache class with old alibaba Activate annotation
     */
    private void cacheActivateClass(Class<?> clazz, String name) {
        Activate activate = clazz.getAnnotation(Activate.class);
        if (activate != null) {
            cachedActivates.put(name, activate);
        } else {
            // support com.alibaba.dubbo.common.extension.Activate
            com.alibaba.dubbo.common.extension.Activate oldActivate = clazz.getAnnotation(com.alibaba.dubbo.common.extension.Activate.class);
            if (oldActivate != null) {
                cachedActivates.put(name, oldActivate);
            }
        }
    }

    /**
     * cache Adaptive class which is annotated with <code>Adaptive</code>
     */
    private void cacheAdaptiveClass(Class<?> clazz) {
        if (cachedAdaptiveClass == null) {
            cachedAdaptiveClass = clazz;
        } else if (!cachedAdaptiveClass.equals(clazz)) {
            throw new IllegalStateException("More than 1 adaptive class found: "
                    + cachedAdaptiveClass.getClass().getName()
                    + ", " + clazz.getClass().getName());
        }
    }

    /**
     * cache wrapper class
     * <p>
     * like: ProtocolFilterWrapper, ProtocolListenerWrapper
     */
    private void cacheWrapperClass(Class<?> clazz) {
        if (cachedWrapperClasses == null) {
            cachedWrapperClasses = new ConcurrentHashSet<>();
        }
        cachedWrapperClasses.add(clazz);
    }

    /**
     * test if clazz is a wrapper class
     * <p>
     * which has Constructor with given class type as its only argument
     */
    private boolean isWrapperClass(Class<?> clazz) {
        try {
            clazz.getConstructor(type);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private String findAnnotationName(Class<?> clazz) {
        org.apache.dubbo.common.Extension extension = clazz.getAnnotation(org.apache.dubbo.common.Extension.class);
        if (extension == null) {
            String name = clazz.getSimpleName();
            if (name.endsWith(type.getSimpleName())) {
                name = name.substring(0, name.length() - type.getSimpleName().length());
            }
            return name.toLowerCase();
        }
        return extension.value();
    }

    /**
     * 此方法为创建扩展类，包含了三个步骤：
     * 第一步：获取扩展类的Class对象  ===> Class clazz = getAdaptiveExtensionClass()方法
     * 第二步：使用Class对象创建实例  ===> T t = clazz.newInstance();
     * 第三步：注入扩展类(所谓的dubbo的aop和ioc)  ===> injectExtension(t)
     *
     * 第一次调用时，是type为org.apache.dubbo.common.extension.ExtensionFactory的extensionLoader
     * 的getAdaptiveExtension方法调过来的。
     * @see ExtensionLoader#ExtensionLoader(java.lang.Class)
     * 的这段代码：
     * ExtensionLoader.getExtensionLoader(ExtensionFactory.class).getAdaptiveExtension()
     *
     * @return
     */
    @SuppressWarnings("unchecked")
    private T createAdaptiveExtension() {
        try {
            // 在此处(getAdaptiveExtensionClass().newInstance())会执行对应type的实现类的默认构造方法
            // 其中特别要注意的是org.apache.dubbo.common.extension.factory.AdaptiveExtensionFactory类的构造方法
            // 因为在获取type为ExtensionFactory的ExtensionLoader时，会调用到它
            return injectExtension((T) getAdaptiveExtensionClass().newInstance());
        } catch (Exception e) {
            throw new IllegalStateException("Can't create adaptive extension " + type + ", cause: " + e.getMessage(), e);
        }
    }

    /**
     * 此方法为createAdaptiveExtension方法的第一个步骤：获取扩展类的Class对象
     * 主要是通过getExtensionClasses方法来处理的
     * @see ExtensionLoader#getExtensionClasses()
     *
     * 此方法的总结大致如下：
     * getExtensionClasses()方法主要是获取 对应type的所有SPI中配置的类
     * 如果这些配置的类中有类被标识了@Adaptive注解，此时就直接return了，dubbo则认为自适应扩展类的逻辑已经手工完成
     * 如果这些配置的类中都没有被@Apdative注解标识，此时dubbo就是创建自适应扩展类
     * @return
     */
    private Class<?> getAdaptiveExtensionClass() {
        // 这个步骤完成后，当前ExtensionLoader对应type的spi文件全部加载完成，同时当前
        // ExtensionLoader的cachedClasses属性中存了所有type的实现类
        getExtensionClasses();

        /**
         * 如果当前ExtensionLoader对应type的spi配置的实现类中有@Adaptive注解标识的类，则直接返回它
         * cachedAdaptiveClass的缓存是在如下方法中完成的
         * @see ExtensionLoader#loadClass(java.util.Map, java.net.URL, java.lang.Class, java.lang.String)
         */
        if (cachedAdaptiveClass != null) {
            return cachedAdaptiveClass;
        }

        // 由框架创建自适应扩展类
        return cachedAdaptiveClass = createAdaptiveExtensionClass();
    }

    /**
     * 创建自适应扩展类逻辑：
     * 1、首先校验接口中是否至少存在一个方法被@Adaptvice注解标识了
     * @see AdaptiveClassCodeGenerator#hasAdaptiveMethod()
     * 2、通过@Adaptive注解校验后，就会动态生成adaptive类
     * 3、
     * @return
     */
    private Class<?> createAdaptiveExtensionClass() {
        String code = new AdaptiveClassCodeGenerator(type, cachedDefaultName).generate();
        ClassLoader classLoader = findClassLoader();
        org.apache.dubbo.common.compiler.Compiler compiler = ExtensionLoader.getExtensionLoader(org.apache.dubbo.common.compiler.Compiler.class).getAdaptiveExtension();
        return compiler.compile(code, classLoader);
    }

    @Override
    public String toString() {
        return this.getClass().getName() + "[" + type.getName() + "]";
    }

}
