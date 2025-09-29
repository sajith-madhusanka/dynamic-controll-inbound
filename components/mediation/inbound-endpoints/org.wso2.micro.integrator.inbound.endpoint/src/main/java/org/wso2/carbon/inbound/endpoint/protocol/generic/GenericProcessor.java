/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
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
package org.wso2.carbon.inbound.endpoint.protocol.generic;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.SynapseException;
import org.apache.synapse.config.SynapseConfiguration;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.inbound.InboundProcessorParams;
import org.apache.synapse.libraries.LibClassLoader;
import org.apache.synapse.startup.quartz.StartUpController;
import org.apache.synapse.task.TaskStartupObserver;
import org.wso2.carbon.inbound.endpoint.common.InboundRequestProcessorImpl;
import org.wso2.carbon.inbound.endpoint.common.InboundTask;
import org.wso2.carbon.inbound.endpoint.protocol.PollingConstants;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;

public class GenericProcessor extends InboundRequestProcessorImpl implements TaskStartupObserver {

    private GenericPollingConsumer pollingConsumer;
    private Properties properties;
    private String injectingSeq;
    private String onErrorSeq;
    private static final Log log = LogFactory.getLog(GenericProcessor.class);
    private StartUpController startUpController;
    private String classImpl;
    private boolean sequential;

    private static final String ENDPOINT_POSTFIX = "CLASS" + COMMON_ENDPOINT_POSTFIX;

    public GenericProcessor(String name, String classImpl, Properties properties, long scanInterval,
                            String injectingSeq, String onErrorSeq, SynapseEnvironment synapseEnvironment,
                            boolean coordination, boolean sequential, boolean startInPauseMode) {
        this.name = name;
        this.properties = properties;
        this.interval = scanInterval;
        this.injectingSeq = injectingSeq;
        this.onErrorSeq = onErrorSeq;
        this.synapseEnvironment = synapseEnvironment;
        this.classImpl = classImpl;
        this.coordination = coordination;
        this.sequential = sequential;
        this.startInPausedMode = startInPauseMode;
    }

    public GenericProcessor(InboundProcessorParams params) {
        this.name = params.getName();
        this.properties = params.getProperties();
        this.interval = Long.parseLong(properties.getProperty(PollingConstants.INBOUND_ENDPOINT_INTERVAL));
        this.coordination = true;
        if (properties.getProperty(PollingConstants.INBOUND_COORDINATION) != null) {
            this.coordination = Boolean.parseBoolean(properties.getProperty(PollingConstants.INBOUND_COORDINATION));
        }
        this.sequential = true;
        if (properties.getProperty(PollingConstants.INBOUND_ENDPOINT_SEQUENTIAL) != null) {
            this.sequential = Boolean
                    .parseBoolean(properties.getProperty(PollingConstants.INBOUND_ENDPOINT_SEQUENTIAL));
        }
        this.injectingSeq = params.getInjectingSeq();
        this.onErrorSeq = params.getOnErrorSeq();
        this.synapseEnvironment = params.getSynapseEnvironment();
        this.classImpl = params.getClassImpl();
        this.startInPausedMode = params.startInPausedMode();
    }

    public void init() {
        log.info("Inbound listener [" + name + "] is initializing"
                + (this.startInPausedMode ? " but will remain in suspended mode..." : "..."));
        Map<String, ClassLoader> libClassLoaders = SynapseConfiguration.getLibraryClassLoaders();
        Class c = null;
        if (libClassLoaders != null) {
            for (Map.Entry<String, ClassLoader> entry : libClassLoaders.entrySet()) {
                try {
                    if (entry.getValue() instanceof LibClassLoader) {
                        c = entry.getValue().loadClass(classImpl);
                        break;
                    }
                } catch (ClassNotFoundException e) {
                    if (log.isDebugEnabled()) {
                        log.debug("Class " + classImpl + " not found in the classloader of the library " + entry.getKey());
                    }
                }
            }
        }
        if (c == null) {
            try {
                c = Class.forName(classImpl);
            } catch (ClassNotFoundException e) {
                handleException(
                        "Class " + classImpl + " not found. Please check the required class is added to the classpath.", e);
            }
        }
        try {
            Constructor cons = c
                    .getConstructor(Properties.class, String.class, SynapseEnvironment.class, long.class, String.class,
                            String.class, boolean.class, boolean.class);
            pollingConsumer = (GenericPollingConsumer) cons
                    .newInstance(properties, name, synapseEnvironment, interval, injectingSeq, onErrorSeq, coordination,
                            sequential);
        } catch (NoSuchMethodException e) {
            handleException("Required constructor is not implemented.", e);
        } catch (InvocationTargetException e) {
            handleException("Unable to create the consumer", e);
        } catch (Exception e) {
            handleException("Unable to create the consumer", e);
        }
        start();
    }

    private void handleException(String msg, Exception ex) {
        log.error(msg, ex);
        throw new SynapseException(ex);
    }

    public void start() {
        try {
            InboundTask task = new GenericTask(pollingConsumer, interval);
            start(task, ENDPOINT_POSTFIX);
        } catch (Exception e) {
            log.error("Could not start Generic Processor. Error starting up scheduler. Error: " + e
                    .getLocalizedMessage());
        }
    }

    /**
     * Stop the inbound polling processor This will be called when inbound is
     * undeployed/redeployed or when server stop
     */
    public void destroy() {
        super.destroy();
        //Terminate polling events
        pollingConsumer.destroy();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void update() {
        start();
    }

    @Override
    public boolean activate() {
        pollingConsumer.resume();
        return super.activate();
    }

    @Override
    public boolean deactivate() {
        pollingConsumer.destroy();
        return super.deactivate();
    }
}
