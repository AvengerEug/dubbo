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
package org.apache.dubbo.config.spring;

import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ConfigCenterConfig;
import org.apache.dubbo.config.MetadataReportConfig;
import org.apache.dubbo.config.MetricsConfig;
import org.apache.dubbo.config.ModuleConfig;
import org.apache.dubbo.config.MonitorConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.ProviderConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.apache.dubbo.config.annotation.Service;
import org.apache.dubbo.config.spring.context.event.ServiceBeanExportedEvent;
import org.apache.dubbo.config.spring.extension.SpringExtensionFactory;

import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SPLIT_PATTERN;
import static org.apache.dubbo.config.spring.util.BeanFactoryUtils.addApplicationListener;

/**
 * ServiceFactoryBean
 *
 * 是spring框架和dubbo框架的桥梁，
 * 即dubbo使用了spring的一些扩展点将dubbo与spring进行无缝对接。
 * PS: ReferenceBean类也有相似的功能，也是dubbo与spring的桥梁
 *
 * ServiceBean：当我们在xml中配置一个<dubbo:service 标签时，就会对应一个ServiceBean对象，
 * spring会对ServiceBean进行初始化，因为它继承了ServiceConfig，根据java特性，会先初始化
 * ServiceConfig类
 *
 * @export
 */
public class ServiceBean<T> extends ServiceConfig<T> implements InitializingBean, DisposableBean,
        ApplicationContextAware, ApplicationListener<ContextRefreshedEvent>, BeanNameAware,
        ApplicationEventPublisherAware {


    private static final long serialVersionUID = 213195494150089726L;

    private final transient Service service;

    private transient ApplicationContext applicationContext;

    private transient String beanName;

    /**
     *
     * 因为此类实现了ApplicationContextAware和ApplicationEventPublisherAware接口
     * 在ApplicationEventPublisherAware中的postProcessBeforeInitialization方法中会对当前bean填充一些
     * spring上下文相关的对象，详看如下方法
     * @see org.springframework.context.support.ApplicationContextAwareProcessor#invokeAwareInterfaces(java.lang.Object)
     * 而此属性的填充就是因为此bean实现了ApplicationContextAware接口
     *
     *
     * 该变量用于表示当前的Spring容器是否支持ApplicationListener ===> TODO 难道还能关闭spring的事件驱动模型？
     * 该变量的初始值为false。Dubbo使用的spring的ApplicationContextAware扩展点来对此属性做了赋值，
     * 因为spring的ApplicationContextAware扩展点可以获取到spring的上下文，然后通过spring的上下文
     * 来判断spring是否支持事件驱动模型。
     * 具体操作supportedApplicationListener变量的过程参考如下方法：
     * @see ServiceBean#setApplicationContext(org.springframework.context.ApplicationContext)
     */
    private transient boolean supportedApplicationListener;

    /**
     * 在ApplicationEventPublisherAware中的postProcessBeforeInitialization方法中会对当前bean填充一些
     * spring上下文相关的对象，详看如下方法
     * @see org.springframework.context.support.ApplicationContextAwareProcessor#invokeAwareInterfaces(java.lang.Object)
     * 而此属性的填充就是因为此bean实现了ApplicationEventPublisherAware接口
     */
    private ApplicationEventPublisher applicationEventPublisher;

    public ServiceBean() {
        super();
        this.service = null;
    }

    /**
     * Spring在创建bean时肯定会选择一个构造方法来执行，
     * 具体使用哪一个肯定在构建ServiceBean的BeanDefinition时来决定的，
     * 这个待处理
     * TODO 专门写一篇博客来描述spring在构建ServiceBean时如何来选择构造方法的
     * @param service
     */
    public ServiceBean(Service service) {
        super(service);
        this.service = service;
    }

    /**
     * 此方法为Spring ApplicationContextAware扩展点，
     * 在spring执行refresh方法的初始化bean方法时，会触发进行后置处理器BeanPostProcessor的回调，
     * 最主要是如下后置处理器的回调，最终会调用到此方法来。
     * 大致就是bean创建后, 调用BeanPostProcessor的postProcessBeforeInitialization方法，进而对ApplicationContext进行填充
     * @see org.springframework.context.support.ApplicationContextAwareProcessor
     * TODO 问题来了，那不是每个serviceBean都会调用SpringExtensionFactory的applicationContext方法？虽然方法内部是用了一个set来存储，可以去重，但是有必要这么设计吗？
     * @param applicationContext
     */
    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
        SpringExtensionFactory.addApplicationContext(applicationContext);
        //TODO 怎么从这里调用到org.apache.dubbo.config.spring.util.BeanFactoryUtils.addApplicationListener取得？ ===> 看53行代码，它把静态方法给导入进来了
        supportedApplicationListener = addApplicationListener(applicationContext, this);
    }

    @Override
    public void setBeanName(String name) {
        this.beanName = name;
    }

    /**
     * Gets associated {@link Service}
     *
     * @return associated {@link Service}
     */
    public Service getService() {
        return service;
    }

    /**
     * 服务导出的入口：spring容器初始化完成，发布refresh事件
     * @param event
     */
    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        // 判断是否满足导出服务条件  => 服务未导出
        if (!isExported() && !isUnexported()) {
            if (logger.isInfoEnabled()) {
                logger.info("The service ready on spring started. service: " + getInterface());
            }
            // 服务导出过程使用了责任链模式，一层一层的调用父类相同的方法，由每一个类专心处理自己的事
            export();
        }
    }

    /**
     * 利用spring InitializingBean的扩展点，对当前服务配置的一些属性进行手动注入，
     * eg: 一个服务对应的provider、application、module等等
     * @throws Exception
     */
    @Override
    @SuppressWarnings({"unchecked", "deprecation"})
    public void afterPropertiesSet() throws Exception {
        if (getProvider() == null) {
            Map<String, ProviderConfig> providerConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, ProviderConfig.class, false, false);
            if (providerConfigMap != null && providerConfigMap.size() > 0) {
                Map<String, ProtocolConfig> protocolConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, ProtocolConfig.class, false, false);
                if (CollectionUtils.isEmptyMap(protocolConfigMap)
                        && providerConfigMap.size() > 1) { // backward compatibility
                    List<ProviderConfig> providerConfigs = new ArrayList<ProviderConfig>();
                    for (ProviderConfig config : providerConfigMap.values()) {
                        if (config.isDefault() != null && config.isDefault()) {
                            providerConfigs.add(config);
                        }
                    }
                    if (!providerConfigs.isEmpty()) {
                        setProviders(providerConfigs);
                    }
                } else {
                    ProviderConfig providerConfig = null;
                    for (ProviderConfig config : providerConfigMap.values()) {
                        if (config.isDefault() == null || config.isDefault()) {
                            if (providerConfig != null) {
                                throw new IllegalStateException("Duplicate provider configs: " + providerConfig + " and " + config);
                            }
                            providerConfig = config;
                        }
                    }
                    if (providerConfig != null) {
                        setProvider(providerConfig);
                    }
                }
            }
        }
        if (getApplication() == null
                && (getProvider() == null || getProvider().getApplication() == null)) {
            Map<String, ApplicationConfig> applicationConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, ApplicationConfig.class, false, false);
            if (applicationConfigMap != null && applicationConfigMap.size() > 0) {
                ApplicationConfig applicationConfig = null;
                for (ApplicationConfig config : applicationConfigMap.values()) {
                    if (applicationConfig != null) {
                        throw new IllegalStateException("Duplicate application configs: " + applicationConfig + " and " + config);
                    }
                    applicationConfig = config;
                }
                if (applicationConfig != null) {
                    setApplication(applicationConfig);
                }
            }
        }
         if (getModule() == null
                && (getProvider() == null || getProvider().getModule() == null)) {
            Map<String, ModuleConfig> moduleConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, ModuleConfig.class, false, false);
            if (moduleConfigMap != null && moduleConfigMap.size() > 0) {
                ModuleConfig moduleConfig = null;
                for (ModuleConfig config : moduleConfigMap.values()) {
                    if (config.isDefault() == null || config.isDefault()) {
                        if (moduleConfig != null) {
                            throw new IllegalStateException("Duplicate module configs: " + moduleConfig + " and " + config);
                        }
                        moduleConfig = config;
                    }
                }
                if (moduleConfig != null) {
                    setModule(moduleConfig);
                }
            }
        }

        if (StringUtils.isEmpty(getRegistryIds())) {
            if (getApplication() != null && StringUtils.isNotEmpty(getApplication().getRegistryIds())) {
                setRegistryIds(getApplication().getRegistryIds());
            }
            if (getProvider() != null && StringUtils.isNotEmpty(getProvider().getRegistryIds())) {
                setRegistryIds(getProvider().getRegistryIds());
            }
        }

        if ((CollectionUtils.isEmpty(getRegistries()))
                && (getProvider() == null || CollectionUtils.isEmpty(getProvider().getRegistries()))
                && (getApplication() == null || CollectionUtils.isEmpty(getApplication().getRegistries()))) {
            Map<String, RegistryConfig> registryConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, RegistryConfig.class, false, false);
            if (CollectionUtils.isNotEmptyMap(registryConfigMap)) {
                List<RegistryConfig> registryConfigs = new ArrayList<>();
                if (StringUtils.isNotEmpty(registryIds)) {
                    Arrays.stream(COMMA_SPLIT_PATTERN.split(registryIds)).forEach(id -> {
                        if (registryConfigMap.containsKey(id)) {
                            registryConfigs.add(registryConfigMap.get(id));
                        }
                    });
                }

                if (registryConfigs.isEmpty()) {
                    for (RegistryConfig config : registryConfigMap.values()) {
                        if (StringUtils.isEmpty(registryIds)) {
                            registryConfigs.add(config);
                        }
                    }
                }
                if (!registryConfigs.isEmpty()) {
                    super.setRegistries(registryConfigs);
                }
            }
        }
        if (getMetadataReportConfig() == null) {
            Map<String, MetadataReportConfig> metadataReportConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, MetadataReportConfig.class, false, false);
            if (metadataReportConfigMap != null && metadataReportConfigMap.size() == 1) {
                super.setMetadataReportConfig(metadataReportConfigMap.values().iterator().next());
            } else if (metadataReportConfigMap != null && metadataReportConfigMap.size() > 1) {
                throw new IllegalStateException("Multiple MetadataReport configs: " + metadataReportConfigMap);
            }
        }

        if (getConfigCenter() == null) {
            Map<String, ConfigCenterConfig> configenterMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, ConfigCenterConfig.class, false, false);
            if (configenterMap != null && configenterMap.size() == 1) {
                super.setConfigCenter(configenterMap.values().iterator().next());
            } else if (configenterMap != null && configenterMap.size() > 1) {
                throw new IllegalStateException("Multiple ConfigCenter found:" + configenterMap);
            }
        }

        if (getMonitor() == null
                && (getProvider() == null || getProvider().getMonitor() == null)
                && (getApplication() == null || getApplication().getMonitor() == null)) {
            Map<String, MonitorConfig> monitorConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, MonitorConfig.class, false, false);
            if (monitorConfigMap != null && monitorConfigMap.size() > 0) {
                MonitorConfig monitorConfig = null;
                for (MonitorConfig config : monitorConfigMap.values()) {
                    if (config.isDefault() == null || config.isDefault()) {
                        if (monitorConfig != null) {
                            throw new IllegalStateException("Duplicate monitor configs: " + monitorConfig + " and " + config);
                        }
                        monitorConfig = config;
                    }
                }
                if (monitorConfig != null) {
                    setMonitor(monitorConfig);
                }
            }
        }

        if (getMetrics() == null) {
            Map<String, MetricsConfig> metricsConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, MetricsConfig.class, false, false);
            if (metricsConfigMap != null && metricsConfigMap.size() > 0) {
                MetricsConfig metricsConfig = null;
                for (MetricsConfig config : metricsConfigMap.values()) {
                    if (metricsConfig != null) {
                        throw new IllegalStateException("Duplicate metrics configs: " + metricsConfig + " and " + config);
                    }
                    metricsConfig = config;
                }
                if (metricsConfig != null) {
                    setMetrics(metricsConfig);
                }
            }
        }

        if (StringUtils.isEmpty(getProtocolIds())) {
            if (getProvider() != null && StringUtils.isNotEmpty(getProvider().getProtocolIds())) {
                setProtocolIds(getProvider().getProtocolIds());
            }
        }

        if (CollectionUtils.isEmpty(getProtocols())
                && (getProvider() == null || CollectionUtils.isEmpty(getProvider().getProtocols()))) {
            Map<String, ProtocolConfig> protocolConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, ProtocolConfig.class, false, false);
            if (protocolConfigMap != null && protocolConfigMap.size() > 0) {
                List<ProtocolConfig> protocolConfigs = new ArrayList<ProtocolConfig>();
                if (StringUtils.isNotEmpty(getProtocolIds())) {
                    Arrays.stream(COMMA_SPLIT_PATTERN.split(getProtocolIds()))
                            .forEach(id -> {
                                if (protocolConfigMap.containsKey(id)) {
                                    protocolConfigs.add(protocolConfigMap.get(id));
                                }
                            });
                }

                if (protocolConfigs.isEmpty()) {
                    for (ProtocolConfig config : protocolConfigMap.values()) {
                        if (StringUtils.isEmpty(protocolIds)) {
                            protocolConfigs.add(config);
                        }
                    }
                }

                if (!protocolConfigs.isEmpty()) {
                    super.setProtocols(protocolConfigs);
                }
            }
        }
        if (StringUtils.isEmpty(getPath())) {
            if (StringUtils.isNotEmpty(beanName)
                    && StringUtils.isNotEmpty(getInterface())
                    && beanName.startsWith(getInterface())) {
                setPath(beanName);
            }
        }
        if (!supportedApplicationListener) {
            export();
        }
    }

    /**
     * Get the name of {@link ServiceBean}
     *
     * @return {@link ServiceBean}'s name
     * @since 2.6.5
     */
    public String getBeanName() {
        return this.beanName;
    }

    /**
     * @since 2.6.5
     */
    @Override
    public void export() {
        // 调用父类的export方法， 处于服务导出中三个阶段的第一个阶段：检查参数和组装URL
        /**
         * 检查参数
         * @see ServiceConfig#checkAndUpdateSubConfigs()
         * 组装URL
         * @see ServiceConfig#doExport()
         */
        super.export();
        // Publish ServiceBeanExportedEvent
        publishExportEvent();
    }

    /**
     * @since 2.6.5
     */
    private void publishExportEvent() {
        ServiceBeanExportedEvent exportEvent = new ServiceBeanExportedEvent(this);
        applicationEventPublisher.publishEvent(exportEvent);
    }

    @Override
    public void destroy() throws Exception {
        // no need to call unexport() here, see
        // org.apache.dubbo.config.spring.extension.SpringExtensionFactory.ShutdownHookListener
    }

    // merged from dubbox
    @Override
    protected Class getServiceClass(T ref) {
        if (AopUtils.isAopProxy(ref)) {
            return AopUtils.getTargetClass(ref);
        }
        return super.getServiceClass(ref);
    }

    /**
     * @param applicationEventPublisher
     * @since 2.6.5
     */
    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }
}
