/*
 * Zeebe Broker Core
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package io.zeebe.broker;

import java.io.InputStream;

import io.zeebe.broker.clustering.ClusterComponent;
import io.zeebe.broker.logstreams.LogStreamsComponent;
import io.zeebe.broker.system.SystemComponent;
import io.zeebe.broker.system.SystemContext;
import io.zeebe.broker.task.TaskQueueComponent;
import io.zeebe.broker.transport.TransportComponent;
import io.zeebe.broker.workflow.WorkflowComponent;
import org.slf4j.Logger;

public class Broker implements AutoCloseable
{
    public static final Logger LOG = Loggers.SYSTEM_LOGGER;

    protected final SystemContext brokerContext;

    public Broker(String configFileLocation)
    {
        brokerContext = new SystemContext(configFileLocation);
        start();
    }

    public Broker(InputStream configStream)
    {
        brokerContext = new SystemContext(configStream);
        start();
    }

    protected void start()
    {
        brokerContext.addComponent(new SystemComponent());
        brokerContext.addComponent(new TransportComponent());
        brokerContext.addComponent(new LogStreamsComponent());
        brokerContext.addComponent(new TaskQueueComponent());
        brokerContext.addComponent(new WorkflowComponent());
        brokerContext.addComponent(new ClusterComponent());

        brokerContext.init();
    }

    @Override
    public void close()
    {
        brokerContext.close();

        LOG.info("Broker closed");
    }

    public SystemContext getBrokerContext()
    {
        return brokerContext;
    }
}
