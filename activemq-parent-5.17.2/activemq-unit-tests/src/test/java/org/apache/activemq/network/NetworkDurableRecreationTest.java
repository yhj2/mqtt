/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.ArrayList;

import javax.jms.JMSException;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Session;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.broker.ConnectionContext;
import org.apache.activemq.broker.TransportConnection;
import org.apache.activemq.broker.region.DestinationStatistics;
import org.apache.activemq.command.ActiveMQDestination;
import org.apache.activemq.command.ActiveMQTopic;
import org.apache.activemq.command.RemoveSubscriptionInfo;
import org.apache.activemq.util.Wait;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * This test is to show that if a durable subscription over a network bridge is deleted and
 * re-created, messages will flow properly again for dynamic subscriptions.
 *
 * AMQ-6050
 */
public class NetworkDurableRecreationTest extends DynamicNetworkTestSupport {

    /**
     * Test publisher on localBroker and durable on remoteBroker
     * after durable deletion, recreate durable
     */
    @Test(timeout = 30 * 1000)
    public void testDurableConsumer() throws Exception {
        testReceive(remoteBroker, remoteSession, localBroker, localSession, new ConsumerCreator() {

            @Override
            public MessageConsumer createConsumer() throws JMSException {
                return remoteSession.createDurableSubscriber(included, subName);
            }
        });
    }

    /**
     * Reverse and test publisher on remoteBroker and durable on localBroker
     * after durable deletion, recreate durable
     */
    @Test(timeout = 30 * 1000)
    public void testDurableConsumerReverse() throws Exception {
        testReceive(localBroker, localSession, remoteBroker, remoteSession, new ConsumerCreator() {

            @Override
            public MessageConsumer createConsumer() throws JMSException {
                return localSession.createDurableSubscriber(included, subName);
            }
        });
    }

    /**
     * Test publisher on localBroker and durable on remoteBroker
     * after durable deletion, recreate with a non-durable consumer
     */
    @Test(timeout = 30 * 1000)
    public void testDurableAndTopicConsumer() throws Exception {
        testReceive(remoteBroker, remoteSession, localBroker, localSession, new ConsumerCreator() {

            @Override
            public MessageConsumer createConsumer() throws JMSException {
                return remoteSession.createConsumer(included);
            }
        });
    }

    /**
     * Reverse and test publisher on remoteBroker and durable on localBroker
     * after durable deletion, recreate with a non-durable consumer
     */
    @Test(timeout = 30 * 1000)
    public void testDurableAndTopicConsumerReverse() throws Exception {
        testReceive(localBroker, localSession, remoteBroker, remoteSession, new ConsumerCreator() {

            @Override
            public MessageConsumer createConsumer() throws JMSException {
                return localSession.createConsumer(included);
            }
        });
    }

    protected void testReceive(BrokerService receiveBroker, Session receiveSession,
            BrokerService publishBroker, Session publishSession, ConsumerCreator secondConsumerCreator) throws Exception {

        final DestinationStatistics destinationStatistics =
                publishBroker.getDestination(included).getDestinationStatistics();

        MessageProducer includedProducer = publishSession.createProducer(included);
        MessageConsumer bridgeConsumer = receiveSession.createDurableSubscriber(
                included, subName);

        waitForConsumerCount(destinationStatistics, 1);

        final NetworkBridge bridge;
        if (publishBroker.getNetworkConnectors().size() > 0) {
            Wait.waitFor(() -> publishBroker.getNetworkConnectors().get(0).activeBridges().size() == 1, 10000, 500);
            bridge = publishBroker.getNetworkConnectors().get(0).activeBridges().iterator().next();
        } else {
            bridge = findDuplexBridge(publishBroker.getTransportConnectorByScheme("tcp"));
        }

        //Should be 2 - one for the durable destination and one for the advisory destinations
        assertSubscriptionMapCounts(bridge, 2);

        //remove the durable
        final ConnectionContext context = new ConnectionContext();
        RemoveSubscriptionInfo info = getRemoveSubscriptionInfo(context, receiveBroker);
        bridgeConsumer.close();
        Thread.sleep(1000);
        receiveBroker.getBroker().removeSubscription(context, info);
        waitForConsumerCount(destinationStatistics, 0);

        //Should be 1 - 0 for the durable destination and one for the advisory destinations
        assertSubscriptionMapCounts(bridge, 1);

        //re-create consumer
        MessageConsumer bridgeConsumer2 = secondConsumerCreator.createConsumer();
        waitForConsumerCount(destinationStatistics, 1);

        //make sure message received
        includedProducer.send(publishSession.createTextMessage("test"));
        assertNotNull(bridgeConsumer2.receive(5000));
    }

    @Before
    public void setUp() throws Exception {
        doSetUp(true);
    }

    @After
    public void tearDown() throws Exception {
        doTearDown();
    }

    protected void doTearDown() throws Exception {
        if (localConnection != null) {
            localConnection.close();
        }
        if (remoteConnection != null) {
            remoteConnection.close();
        }
        if (localBroker != null) {
            localBroker.stop();
        }
        if (remoteBroker != null) {
            remoteBroker.stop();
        }
    }


    protected void doSetUp(boolean deleteAllMessages) throws Exception {
        remoteBroker = createRemoteBroker();
        remoteBroker.setDeleteAllMessagesOnStartup(deleteAllMessages);
        remoteBroker.start();
        remoteBroker.waitUntilStarted();
        localBroker = createLocalBroker();
        localBroker.setDeleteAllMessagesOnStartup(deleteAllMessages);
        localBroker.start();
        localBroker.waitUntilStarted();
        URI localURI = localBroker.getVmConnectorURI();
        ActiveMQConnectionFactory fac = new ActiveMQConnectionFactory(localURI);
        fac.setAlwaysSyncSend(true);
        fac.setDispatchAsync(false);
        localConnection = fac.createConnection();
        localConnection.setClientID(clientId);
        localConnection.start();
        URI remoteURI = remoteBroker.getVmConnectorURI();
        fac = new ActiveMQConnectionFactory(remoteURI);
        remoteConnection = fac.createConnection();
        remoteConnection.setClientID(clientId);
        remoteConnection.start();
        included = new ActiveMQTopic(testTopicName);
        localSession = localConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        remoteSession = remoteConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
    }


    protected NetworkConnector connector;
    protected BrokerService createLocalBroker() throws Exception {
        BrokerService brokerService = new BrokerService();
        brokerService.setMonitorConnectionSplits(true);
        brokerService.setDataDirectoryFile(tempFolder.newFolder());
        brokerService.setBrokerName("localBroker");

        connector = new DiscoveryNetworkConnector(new URI("static:(tcp://localhost:61617)"));
        connector.setName("networkConnector");
        connector.setDecreaseNetworkConsumerPriority(false);
        connector.setConduitSubscriptions(true);
        connector.setDuplex(true);
        ArrayList<ActiveMQDestination> includedDestinations = new ArrayList<>();
        includedDestinations.add(new ActiveMQTopic(testTopicName));
        ArrayList<ActiveMQDestination> excludedDestinations = new ArrayList<>();
        excludedDestinations.add(new ActiveMQTopic(excludeTopicName));
        connector.setDynamicallyIncludedDestinations(includedDestinations);
        connector.setExcludedDestinations(excludedDestinations);

        brokerService.addNetworkConnector(connector);
        brokerService.addConnector("tcp://localhost:61616");

        return brokerService;
    }

    protected BrokerService createRemoteBroker() throws Exception {
        BrokerService brokerService = new BrokerService();
        brokerService.setBrokerName("remoteBroker");
        brokerService.setUseJmx(false);
        brokerService.setDataDirectoryFile(tempFolder.newFolder());
        brokerService.addConnector("tcp://localhost:61617");

        return brokerService;
    }

}
