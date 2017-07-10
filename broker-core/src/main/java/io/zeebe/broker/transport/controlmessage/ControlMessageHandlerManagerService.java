/**
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
package io.zeebe.broker.transport.controlmessage;

import java.util.Arrays;
import java.util.List;

import io.zeebe.broker.clustering.gossip.Gossip;
import io.zeebe.broker.clustering.handler.RequestTopologyHandler;
import io.zeebe.broker.event.handler.RemoveTopicSubscriptionHandler;
import io.zeebe.broker.event.processor.TopicSubscriptionService;
import io.zeebe.broker.task.TaskSubscriptionManager;
import io.zeebe.dispatcher.Dispatcher;
import io.zeebe.servicecontainer.Injector;
import io.zeebe.servicecontainer.Service;
import io.zeebe.servicecontainer.ServiceStartContext;
import io.zeebe.servicecontainer.ServiceStopContext;
import io.zeebe.transport.ServerOutput;
import io.zeebe.transport.ServerTransport;
import io.zeebe.util.actor.ActorScheduler;

public class ControlMessageHandlerManagerService implements Service<ControlMessageHandlerManager>
{
    protected final Injector<ServerTransport> transportInjector = new Injector<>();
    protected final Injector<Dispatcher> controlMessageBufferInjector = new Injector<>();
    protected final Injector<ActorScheduler> actorSchedulerInjector = new Injector<>();
    protected final Injector<TaskSubscriptionManager> taskSubscriptionManagerInjector = new Injector<>();
    protected final Injector<TopicSubscriptionService> topicSubscriptionServiceInjector = new Injector<>();
    protected final Injector<Gossip> gossipInjector = new Injector<>();

    protected final long controlMessageRequestTimeoutInMillis;

    protected ControlMessageHandlerManager service;

    public ControlMessageHandlerManagerService(long controlMessageRequestTimeoutInMillis)
    {
        this.controlMessageRequestTimeoutInMillis = controlMessageRequestTimeoutInMillis;
    }

    @Override
    public void start(ServiceStartContext context)
    {
        final Dispatcher controlMessageBuffer = controlMessageBufferInjector.getValue();

        final ServerTransport transport = transportInjector.getValue();
        final ActorScheduler actorScheduler = actorSchedulerInjector.getValue();

        final TaskSubscriptionManager taskSubscriptionManager = taskSubscriptionManagerInjector.getValue();
        final TopicSubscriptionService topicSubscriptionService = topicSubscriptionServiceInjector.getValue();
        final Gossip gossip = gossipInjector.getValue();

        final ServerOutput output = transport.getOutput();

        final List<ControlMessageHandler> controlMessageHandlers = Arrays.asList(
            new AddTaskSubscriptionHandler(output, taskSubscriptionManager),
            new IncreaseTaskSubscriptionCreditsHandler(output, taskSubscriptionManager),
            new RemoveTaskSubscriptionHandler(output, taskSubscriptionManager),
            new RemoveTopicSubscriptionHandler(output, topicSubscriptionService),
            new RequestTopologyHandler(output, gossip)
        );

        service = new ControlMessageHandlerManager(
                transport.getOutput(),
                controlMessageBuffer,
                controlMessageRequestTimeoutInMillis,
                actorScheduler,
                controlMessageHandlers);

        context.async(service.openAsync());
    }

    @Override
    public void stop(ServiceStopContext context)
    {
        context.async(service.closeAsync());
    }

    @Override
    public ControlMessageHandlerManager get()
    {
        return service;
    }

    public Injector<ServerTransport> getTransportInjector()
    {
        return transportInjector;
    }

    public Injector<Dispatcher> getControlMessageBufferInjector()
    {
        return controlMessageBufferInjector;
    }

    public Injector<ActorScheduler> getActorSchedulerInjector()
    {
        return actorSchedulerInjector;
    }

    public Injector<TaskSubscriptionManager> getTaskSubscriptionManagerInjector()
    {
        return taskSubscriptionManagerInjector;
    }

    public Injector<TopicSubscriptionService> getTopicSubscriptionServiceInjector()
    {
        return topicSubscriptionServiceInjector;
    }

    public Injector<Gossip> getGossipInjector()
    {
        return gossipInjector;
    }
}
