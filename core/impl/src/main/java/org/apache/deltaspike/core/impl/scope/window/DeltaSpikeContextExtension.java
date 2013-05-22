/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.deltaspike.core.impl.scope.window;

import javax.enterprise.event.Observes;
import javax.enterprise.inject.spi.AfterBeanDiscovery;
import javax.enterprise.inject.spi.AfterDeploymentValidation;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.Extension;

import org.apache.deltaspike.core.api.provider.BeanProvider;

/**
 * Handle all DeltaSpike WindowContext and ConversationContext
 * related features.
 */
public class DeltaSpikeContextExtension implements Extension
{
    private WindowContextImpl windowContext;

    public void registerDeltaSpikeContexts(@Observes AfterBeanDiscovery afterBeanDiscovery, BeanManager beanManager)
    {
        windowContext = new WindowContextImpl(beanManager);
        afterBeanDiscovery.addContext(windowContext);
    }

    /**
     * We can only initialize our contexts in AfterDeploymentValidation because
     * getBeans must not be invoked earlier than this phase to reduce randomness
     * caused by Beans no being fully registered yet.
     */
    public void initializeDeltaSpikeContexts(@Observes AfterDeploymentValidation adv, BeanManager beanManager)
    {
        WindowBeanHolder windowBeanHolder
            = BeanProvider.getContextualReference(beanManager, WindowBeanHolder.class, false);

        WindowIdHolder windowIdHolder
            = BeanProvider.getContextualReference(beanManager, WindowIdHolder.class, false);

        windowContext.initWindowContext(windowBeanHolder, windowIdHolder);
    }

    public WindowContextImpl getWindowContext()
    {
        return windowContext;
    }
}
