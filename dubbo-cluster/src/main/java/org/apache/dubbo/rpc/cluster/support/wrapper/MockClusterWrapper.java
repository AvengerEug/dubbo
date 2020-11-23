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
package org.apache.dubbo.rpc.cluster.support.wrapper;

import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Cluster;
import org.apache.dubbo.rpc.cluster.Directory;

/**
 * mock impl
 *
 */
public class MockClusterWrapper implements Cluster {

    /**
     * 因为它是一个Wrapper类，因此它会包装所有的Cluster对象
     * 因此，我们可以确定，这个cluster对象，就是我们使用
     * ExtensionLoader获取的那个扩展，
     * 比如，如下代码：
     * ExtensionLoader.getExtensionLoader(Cluster.class).getExtension("failover");
     *
     * 此时获取的是FailoverCluster，但因为存在Wrapper类的原因，
     * 因此，最终获取的是MockClusterWrapper类，而MockClusterWrapper内部的
     * cluster属性就是FailoverCluster了
     *
     * 当然，在缺省情况下，这里的cluster确实就是FailoverCluster
     */
    private Cluster cluster;

    public MockClusterWrapper(Cluster cluster) {

        this.cluster = cluster;
    }

    /**
     * MockClusterWrapper的join方法就是创建了一个MockClusterInvoker，
     * 其中，内部维护了当前被引用服务的服务目录，以及一个invoker对象。
     *
     * 这个invoker对象是由FailoverCluster的join方法产生的，
     * 而failoverCluster的join方法最终就是产生了FailoverClusterInvoker对象，
     * 内部维护了当前被引用服务的服务目录
     *
     * 所以，我们的Cluster的结构为：
     *
     * MockClusterWrapper
     *   - FailoverClusterInvoker
     *
     * @param directory
     * @param <T>
     * @return
     * @throws RpcException
     */
    @Override
    public <T> Invoker<T> join(Directory<T> directory) throws RpcException {

        return new MockClusterInvoker<T>(directory,
                this.cluster.join(directory));
    }

}
