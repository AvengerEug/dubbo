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
package org.apache.dubbo.registry.integration;

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.configcenter.ConfigChangeEvent;
import org.apache.dubbo.configcenter.ConfigChangeType;
import org.apache.dubbo.configcenter.ConfigurationListener;
import org.apache.dubbo.configcenter.DynamicConfiguration;
import org.apache.dubbo.rpc.cluster.Configurator;
import org.apache.dubbo.rpc.cluster.configurator.parser.ConfigParser;

import java.util.Collections;
import java.util.List;

/**
 * AbstractConfiguratorListener
 */
public abstract class AbstractConfiguratorListener implements ConfigurationListener {
    private static final Logger logger = LoggerFactory.getLogger(AbstractConfiguratorListener.class);

    protected List<Configurator> configurators = Collections.emptyList();

    /**
     * 此方法最主要的作用就是：
     * 根据传入进来的key，组装存储在zookeeper的节点，
     * 并对节点进行监听和读取信息，进而转化成Configurators
     * @param key
     */
    protected final void initWith(String key) {
        /**
         * 动态获取配置中心。假设此时获取到的配置中心为zookeeper
         * @see org.apache.dubbo.configcenter.support.zookeeper.ZookeeperDynamicConfiguration
         */
        DynamicConfiguration dynamicConfiguration = DynamicConfiguration.getDynamicConfiguration();
        /**
         * 为配置中心添加监听器，最终会添加到zookeeper的配置中心中
         * @see org.apache.dubbo.configcenter.support.zookeeper.ZookeeperDynamicConfiguration#cacheListener
         */
        dynamicConfiguration.addListener(key, this);
        String rawConfig = dynamicConfiguration.getRule(key, DynamicConfiguration.DEFAULT_GROUP);
        if (!StringUtils.isEmpty(rawConfig)) {
            // 根据指定节点对应zookeeper的节点内容来生成对应的Configurators配置
            genConfiguratorsFromRawRule(rawConfig);
        }
    }

    @Override
    public void process(ConfigChangeEvent event) {
        if (logger.isInfoEnabled()) {
            logger.info("Notification of overriding rule, change type is: " + event.getChangeType() +
                    ", raw config content is:\n " + event.getValue());
        }

        if (event.getChangeType().equals(ConfigChangeType.DELETED)) {
            configurators.clear();
        } else {
            if (!genConfiguratorsFromRawRule(event.getValue())) {
                return;
            }
        }

        notifyOverrides();
    }

    private boolean genConfiguratorsFromRawRule(String rawConfig) {
        boolean parseSuccess = true;
        try {
            // parseConfigurators will recognize app/service config automatically.
            configurators = Configurator.toConfigurators(ConfigParser.parseConfigurators(rawConfig))
                    .orElse(configurators);
        } catch (Exception e) {
            logger.error("Failed to parse raw dynamic config and it will not take effect, the raw config is: " +
                    rawConfig, e);
            parseSuccess = false;
        }
        return parseSuccess;
    }

    protected abstract void notifyOverrides();

    public List<Configurator> getConfigurators() {
        return configurators;
    }

    public void setConfigurators(List<Configurator> configurators) {
        this.configurators = configurators;
    }
}
