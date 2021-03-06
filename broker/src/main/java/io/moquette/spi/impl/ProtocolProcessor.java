/*
 * Copyright (c) 2012-2015 The original author or authors
 * ------------------------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 * The Eclipse Public License is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * The Apache License v2.0 is available at
 * http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */
package io.moquette.spi.impl;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import io.moquette.server.ConnectionDescriptor;
import io.moquette.server.netty.NettyUtils;
import io.moquette.spi.ClientSession;
import io.moquette.spi.IMatchingCondition;
import io.moquette.spi.IMessagesStore;
import io.moquette.spi.ISessionsStore;
import io.moquette.spi.security.IAuthenticator;
import io.moquette.spi.security.IAuthorizator;
import io.moquette.spi.impl.subscriptions.SubscriptionsStore;
import io.moquette.spi.impl.subscriptions.Subscription;

import static io.moquette.parser.netty.Utils.VERSION_3_1;
import static io.moquette.parser.netty.Utils.VERSION_3_1_1;
import io.moquette.proto.messages.AbstractMessage;
import io.moquette.proto.messages.AbstractMessage.QOSType;
import io.moquette.proto.messages.ConnAckMessage;
import io.moquette.proto.messages.ConnectMessage;
import io.moquette.proto.messages.PubAckMessage;
import io.moquette.proto.messages.PubCompMessage;
import io.moquette.proto.messages.PubRecMessage;
import io.moquette.proto.messages.PubRelMessage;
import io.moquette.proto.messages.PublishMessage;
import io.moquette.proto.messages.SubAckMessage;
import io.moquette.proto.messages.SubscribeMessage;
import io.moquette.proto.messages.UnsubAckMessage;
import io.moquette.proto.messages.UnsubscribeMessage;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class responsible to handle the logic of MQTT protocol it's the director of
 * the protocol execution.
 *
 * Used by the front facing class SimpleMessaging.
 *
 * @author andrea
 */
public class ProtocolProcessor {

    static final class WillMessage {
        private final String topic;
        private final ByteBuffer payload;
        private final boolean retained;
        private final QOSType qos;

        public WillMessage(String topic, ByteBuffer payload, boolean retained, QOSType qos) {
            this.topic = topic;
            this.payload = payload;
            this.retained = retained;
            this.qos = qos;
        }

        public String getTopic() {
            return topic;
        }

        public ByteBuffer getPayload() {
            return payload;
        }

        public boolean isRetained() {
            return retained;
        }

        public QOSType getQos() {
            return qos;
        }

    }

    private static final Logger LOG = LoggerFactory.getLogger(ProtocolProcessor.class);

    protected ConcurrentMap<String, ConnectionDescriptor> m_clientIDs;
    private SubscriptionsStore subscriptions;
    private boolean allowAnonymous;
    private IAuthorizator m_authorizator;
    private IMessagesStore m_messagesStore;
    private ISessionsStore m_sessionsStore;
    private IAuthenticator m_authenticator;
    private BrokerInterceptor m_interceptor;
    private String allowedTopic = null;
    private AbstractMessage.QOSType allowedQos = null;

    //maps clientID to Will testament, if specified on CONNECT
    private ConcurrentMap<String, WillMessage> m_willStore = new ConcurrentHashMap<>();

    ProtocolProcessor() {}

    /**
     * @param subscriptions the subscription store where are stored all the existing
     *  clients subscriptions.
     * @param storageService the persistent store to use for save/load of messages
     *  for QoS1 and QoS2 handling.
     * @param sessionsStore the clients sessions store, used to persist subscriptions.
     * @param authenticator the authenticator used in connect messages.
     * @param allowAnonymous true connection to clients without credentials.
     * @param authorizator used to apply ACL policies to publishes and subscriptions.
     * @param interceptor to notify events to an intercept handler
     */
    void init(SubscriptionsStore subscriptions, IMessagesStore storageService,
              ISessionsStore sessionsStore,
              IAuthenticator authenticator,
              boolean allowAnonymous, IAuthorizator authorizator, BrokerInterceptor interceptor) {
        init(subscriptions, storageService, sessionsStore, authenticator, allowAnonymous, authorizator, interceptor, null, null);
    }

    void init(SubscriptionsStore subscriptions, IMessagesStore storageService,
              ISessionsStore sessionsStore,
              IAuthenticator authenticator,
              boolean allowAnonymous, IAuthorizator authorizator, BrokerInterceptor interceptor, String allowedTopic, AbstractMessage.QOSType allowedQos) {
        this.m_clientIDs = new ConcurrentHashMap<>();
        this.m_interceptor = interceptor;
        this.subscriptions = subscriptions;
        this.allowAnonymous = allowAnonymous;
        this.allowedTopic = allowedTopic;
        this.allowedQos = allowedQos;
        m_authorizator = authorizator;
        LOG.trace("subscription tree on init {}", subscriptions.dumpTree());
        m_authenticator = authenticator;
        m_messagesStore = storageService;
        m_sessionsStore = sessionsStore;
    }

    public void processConnect(Channel channel, ConnectMessage msg) {
        LOG.debug("CONNECT for client <{}>", msg.getClientID());
        if (msg.getProtocolVersion() != VERSION_3_1 && msg.getProtocolVersion() != VERSION_3_1_1) {
            ConnAckMessage badProto = new ConnAckMessage();
            badProto.setReturnCode(ConnAckMessage.UNNACEPTABLE_PROTOCOL_VERSION);
            LOG.warn("processConnect sent bad proto ConnAck");
            channel.writeAndFlush(badProto);
            channel.close();
            return;
        }

        if (msg.getClientID() == null || msg.getClientID().length() == 0) {
            ConnAckMessage okResp = new ConnAckMessage();
            okResp.setReturnCode(ConnAckMessage.IDENTIFIER_REJECTED);
            channel.writeAndFlush(okResp);
            return;
        }

        //handle user authentication
        if (msg.isUserFlag()) {
            byte[] pwd = null;
            if (msg.isPasswordFlag()) {
                pwd = msg.getPassword();
            } else if (!this.allowAnonymous) {
                failedCredentials(channel);
                return;
            }
            byte validationResultCode = m_authenticator.checkValid(msg, channel.hashCode(), m_clientIDs.containsKey(msg.getClientID()));
            if (validationResultCode != ConnAckMessage.CONNECTION_ACCEPTED) {
                ConnAckMessage okResp = new ConnAckMessage();
                okResp.setReturnCode(validationResultCode);
                channel.writeAndFlush(okResp);
                channel.close();
                return;
            }
            NettyUtils.userName(channel, msg.getUsername());
        } else if (!this.allowAnonymous) {
            failedCredentials(channel);
            return;
        }

        LOG.info("Duplicate connection for client {}?  {}", msg.getClientID(), m_clientIDs.containsKey(msg.getClientID()));

        ConnectionDescriptor connDescr = new ConnectionDescriptor(msg.getClientID(), channel, msg.isCleanSession());
        m_clientIDs.put(msg.getClientID(), connDescr);

        int keepAlive = msg.getKeepAlive();
        LOG.debug("Connect with keepAlive {} s",  keepAlive);
        NettyUtils.keepAlive(channel, keepAlive);
        //session.attr(NettyUtils.ATTR_KEY_CLEANSESSION).set(msg.isCleanSession());
        NettyUtils.cleanSession(channel, msg.isCleanSession());
        //used to track the client in the subscription and publishing phases.
        //session.attr(NettyUtils.ATTR_KEY_CLIENTID).set(msg.getClientID());
        NettyUtils.clientID(channel, msg.getClientID());
        LOG.debug("Connect create session <{}>", channel);

        setIdleTime(channel.pipeline(), Math.round(keepAlive * 1.5f));

        //Handle will flag
        if (msg.isWillFlag()) {
            AbstractMessage.QOSType willQos = AbstractMessage.QOSType.valueOf(msg.getWillQos());
            byte[] willPayload = msg.getWillMessage();
            ByteBuffer bb = (ByteBuffer) ByteBuffer.allocate(willPayload.length).put(willPayload).flip();
            //save the will testament in the clientID store
            WillMessage will = new WillMessage(msg.getWillTopic(), bb, msg.isWillRetain(),willQos );
            m_willStore.put(msg.getClientID(), will);
        }

        ConnAckMessage okResp = new ConnAckMessage();
        okResp.setReturnCode(ConnAckMessage.CONNECTION_ACCEPTED);

        ClientSession clientSession = m_sessionsStore.sessionForClient(msg.getClientID());
        boolean isSessionAlreadyStored = clientSession != null;
        if (!msg.isCleanSession() && isSessionAlreadyStored) {
            okResp.setSessionPresent(true);
        }
        if (isSessionAlreadyStored) {
            clientSession.cleanSession(msg.isCleanSession());
        }
        channel.writeAndFlush(okResp);
        m_interceptor.notifyClientConnected(msg);

        if (!isSessionAlreadyStored) {
            LOG.info("Create persistent session for clientID <{}>", msg.getClientID());
            clientSession = m_sessionsStore.createNewSession(msg.getClientID(), msg.isCleanSession());
        }
        clientSession.activate();
        if (msg.isCleanSession()) {
            clientSession.cleanSession();
        }
        LOG.info("Connected client ID <{}> with clean session {}", msg.getClientID(), msg.isCleanSession());
        if (!msg.isCleanSession()) {
            //force the republish of stored QoS1 and QoS2
            republishStoredInSession(clientSession);
        }
        LOG.info("CONNECT processed");
    }

    private void setIdleTime(ChannelPipeline pipeline, int idleTime) {
        if (pipeline.names().contains("idleStateHandler")) {
            pipeline.remove("idleStateHandler");
        }
        pipeline.addFirst("idleStateHandler", new IdleStateHandler(0, 0, idleTime));
    }

    private void failedCredentials(Channel session) {
        ConnAckMessage okResp = new ConnAckMessage();
        okResp.setReturnCode(ConnAckMessage.BAD_USERNAME_OR_PASSWORD);
        session.writeAndFlush(okResp);
    }

    /**
     * Republish QoS1 and QoS2 messages stored into the session for the clientID.
     * */
    private void republishStoredInSession(ClientSession clientSession) {
        LOG.trace("republishStoredInSession for client <{}>", clientSession);
        List<IMessagesStore.StoredMessage> publishedEvents = clientSession.storedMessages();
        if (publishedEvents.isEmpty()) {
            LOG.info("No stored messages for client <{}>", clientSession.clientID);
            return;
        }

        LOG.info("republishing stored messages to client <{}>", clientSession.clientID);
        for (IMessagesStore.StoredMessage pubEvt : publishedEvents) {
            //TODO put in flight zone
            directSend(clientSession, pubEvt.getTopic(), pubEvt.getQos(),
                    pubEvt.getMessage(), false, pubEvt.getMessageID());
            clientSession.removeEnqueued(pubEvt.getGuid());
        }
    }

    public void processPubAck(Channel session, PubAckMessage msg) {
        String clientID = NettyUtils.clientID(session);
        int messageID = msg.getMessageID();
        //Remove the message from message store
        ClientSession targetSession = m_sessionsStore.sessionForClient(clientID);
        verifyToActivate(clientID, targetSession);
        targetSession.inFlightAcknowledged(messageID);
        m_interceptor.notifyTopicPubacked(msg, clientID);
    }

    private void verifyToActivate(String clientID, ClientSession targetSession) {
        if (m_clientIDs.containsKey(clientID)) {
            targetSession.activate();
        }
    }

    private static IMessagesStore.StoredMessage asStoredMessage(PublishMessage msg) {
        IMessagesStore.StoredMessage stored = new IMessagesStore.StoredMessage(msg.getPayload().array(), msg.getQos(), msg.getTopicName());
        stored.setRetained(msg.isRetainFlag());
        stored.setMessageID(msg.getMessageID());
        return stored;
    }

    private static IMessagesStore.StoredMessage asStoredMessage(WillMessage will) {
        IMessagesStore.StoredMessage pub = new IMessagesStore.StoredMessage(will.getPayload().array(), will.getQos(), will.getTopic());
        pub.setRetained(will.isRetained());
        return pub;
    }
    
    public void processPublish(Channel session, PublishMessage msg) {
        LOG.debug("allowedQos is {}; received Qos is {}", allowedQos, msg.getQos());
        LOG.debug("allowedTopic is {}; received Topic is {}", allowedTopic, msg.getTopicName());

        if ((allowedQos != null && msg.getQos() != allowedQos) || (allowedTopic != null && !msg.getTopicName().equals(allowedTopic))) {
            String clientID = NettyUtils.clientID(session);
            Integer messageID = msg.getMessageID();

            if (msg.getQos() == QOSType.EXACTLY_ONCE) sendPubRec(clientID, messageID);
            else if (msg.getQos() == QOSType.LEAST_ONE) sendPubAck(clientID, messageID);

            return;
        }

        String clientID = NettyUtils.clientID(session);
        Integer messageID = msg.getMessageID();

        if (msg.getQos() == QOSType.EXACTLY_ONCE) {
            sendPubRec(clientID, messageID);
        } else if (msg.getQos() == QOSType.LEAST_ONE) {
            sendPubAck(clientID, messageID);
        }

        m_interceptor.notifyTopicPublished(msg, clientID);
    }

    public void sendContentMessage(String clientId, byte[] message, int packetId) {
        ClientSession targetSession = m_sessionsStore.sessionForClient(clientId);
        verifyToActivate(clientId, targetSession);

        ByteBuffer payload = ByteBuffer.wrap(message);
        directSend(targetSession, "grouvi_client", QOSType.LEAST_ONE, payload, false, packetId);
    }

    public void sendSystemMessage(String clientId, byte[] message) {
        ClientSession targetSession = m_sessionsStore.sessionForClient(clientId);
        verifyToActivate(clientId, targetSession);

        ByteBuffer payload = ByteBuffer.wrap(message);
        directSend(targetSession, "grouvi_client", QOSType.MOST_ONE, payload, false, null);
    }

    public void disconnectClient(String clientId, byte[] error) {
        LOG.info("Forcing client with id <{}> to close", clientId);
        //clean the subscriptions if the old used a cleanSession = true
        Channel channel = m_clientIDs.get(clientId).channel;
        ClientSession clientSession = m_sessionsStore.sessionForClient(clientId);

        if (error != null) {
            ByteBuffer payload = ByteBuffer.wrap(error);
            directSend(clientSession, "grouvi_client", QOSType.MOST_ONE, payload, false, null);
        }

        clientSession.disconnect();
        NettyUtils.sessionStolen(channel, true);
        channel.close();
        LOG.debug("Connection with client ID <{}> is forced to close", clientId);
    }

    /**
     * Intended usage is only for embedded versions of the broker, where the hosting application want to use the
     * broker to send a publish message.
     * Inspired by {@link #processPublish} but with some changes to avoid security check, and the handshake phases
     * for Qos1 and Qos2.
     * It also doesn't notifyTopicPublished because using internally the owner should already know where
     * it's publishing.
     * */
    public void internalPublish(PublishMessage msg) {
        final AbstractMessage.QOSType qos = msg.getQos();
        final String topic = msg.getTopicName();
        LOG.info("embedded PUBLISH on topic <{}> with QoS {}", topic, qos);

        String guid = null;
        IMessagesStore.StoredMessage toStoreMsg = asStoredMessage(msg);
        toStoreMsg.setClientID("BROKER_SELF");
        toStoreMsg.setMessageID(1);
        if (qos == AbstractMessage.QOSType.EXACTLY_ONCE) { //QoS2
            guid = m_messagesStore.storePublishForFuture(toStoreMsg);
        }
        route2Subscribers(toStoreMsg);

        if (!msg.isRetainFlag()) {
            return;
        }
        if (qos == AbstractMessage.QOSType.MOST_ONE || !msg.getPayload().hasRemaining()) {
            //QoS == 0 && retain => clean old retained
            m_messagesStore.cleanRetained(topic);
            return;
        }
        if (guid == null) {
            //before wasn't stored
            guid = m_messagesStore.storePublishForFuture(toStoreMsg);
        }
        m_messagesStore.storeRetained(topic, guid);
    }

    /**
     * Specialized version to publish will testament message.
     */
    private void forwardPublishWill(WillMessage will, String clientID) {
        //it has just to publish the message downstream to the subscribers
        //NB it's a will publish, it needs a PacketIdentifier for this conn, default to 1
        Integer messageId = null;
        if (will.getQos() != AbstractMessage.QOSType.MOST_ONE) {
            messageId = m_sessionsStore.nextPacketID(clientID);
        }

        IMessagesStore.StoredMessage tobeStored = asStoredMessage(will);
        tobeStored.setClientID(clientID);
        tobeStored.setMessageID(messageId);
        route2Subscribers(tobeStored);
    }


    /**
     * Flood the subscribers with the message to notify. MessageID is optional and should only used for QoS 1 and 2
     * */
    void route2Subscribers(IMessagesStore.StoredMessage pubMsg) {
        final String topic = pubMsg.getTopic();
        final AbstractMessage.QOSType publishingQos = pubMsg.getQos();
        final ByteBuffer origMessage = pubMsg.getMessage();
        LOG.debug("route2Subscribers republishing to existing subscribers that matches the topic {}", topic);
        if (LOG.isTraceEnabled()) {
            LOG.trace("content <{}>", DebugUtils.payload2Str(origMessage));
            LOG.trace("subscription tree {}", subscriptions.dumpTree());
        }
        //if QoS 1 or 2 store the message
        String guid = null;
        if (publishingQos == QOSType.EXACTLY_ONCE || publishingQos == QOSType.LEAST_ONE) {
            guid = m_messagesStore.storePublishForFuture(pubMsg);
        }

        for (final Subscription sub : subscriptions.matches(topic)) {
            AbstractMessage.QOSType qos = publishingQos;
            if (qos.byteValue() > sub.getRequestedQos().byteValue()) {
                qos = sub.getRequestedQos();
            }
            ClientSession targetSession = m_sessionsStore.sessionForClient(sub.getClientId());
            verifyToActivate(sub.getClientId(), targetSession);

            LOG.debug("Broker republishing to client <{}> topic <{}> qos <{}>, active {}",
                    sub.getClientId(), sub.getTopicFilter(), qos, targetSession.isActive());
            ByteBuffer message = origMessage.duplicate();
            if (qos == AbstractMessage.QOSType.MOST_ONE && targetSession.isActive()) {
                //QoS 0
                directSend(targetSession, topic, qos, message, false, null);
            } else {
                //QoS 1 or 2
                //if the target subscription is not clean session and is not connected => store it
                if (!targetSession.isCleanSession() && !targetSession.isActive()) {
                    //store the message in targetSession queue to deliver
                    targetSession.enqueueToDeliver(guid);
                } else  {
                    //publish
                    if (targetSession.isActive()) {
                        int messageId = targetSession.nextPacketId();
                        targetSession.inFlightAckWaiting(guid, messageId);
                        directSend(targetSession, topic, qos, message, false, messageId);
                    }
                }
            }
        }
    }

    protected void directSend(ClientSession clientsession, String topic, AbstractMessage.QOSType qos, ByteBuffer message, boolean retained, Integer messageID) {
        String clientId = clientsession.clientID;
        LOG.debug("directSend invoked clientId <{}> on topic <{}> QoS {} retained {} messageID {}", clientId, topic, qos, retained, messageID);
        PublishMessage pubMessage = new PublishMessage();
        pubMessage.setRetainFlag(retained);
        pubMessage.setTopicName(topic);
        pubMessage.setQos(qos);
        pubMessage.setPayload(message);

        if (LOG.isDebugEnabled()) {
            LOG.debug("content <{}>", DebugUtils.payload2Str(message));
        }
        //set the PacketIdentifier only for QoS > 0
        if (pubMessage.getQos() != AbstractMessage.QOSType.MOST_ONE) {
            pubMessage.setMessageID(messageID);
        } else {
            if (messageID != null) {
                throw new RuntimeException("Internal bad error, trying to forwardPublish a QoS 0 message with PacketIdentifier: " + messageID);
            }
        }

        if (m_clientIDs == null) {
            throw new RuntimeException("Internal bad error, found m_clientIDs to null while it should be initialized, somewhere it's overwritten!!");
        }
        LOG.debug("clientIDs are {}", m_clientIDs);
        if (m_clientIDs.get(clientId) == null) {
            //TODO while we were publishing to the target client, that client disconnected,
            // could happen is not an error HANDLE IT
            throw new RuntimeException(String.format("Can't find a ConnectionDescriptor for client <%s> in cache <%s>", clientId, m_clientIDs));
        }
        Channel channel = m_clientIDs.get(clientId).channel;
        LOG.debug("Session for clientId {} is {}", clientId, channel);
        channel.writeAndFlush(pubMessage);
    }

    private void sendPubRec(String clientID, int messageID) {
        LOG.trace("PUB <--PUBREC-- SRV sendPubRec invoked for clientID {} with messageID {}", clientID, messageID);
        PubRecMessage pubRecMessage = new PubRecMessage();
        pubRecMessage.setMessageID(messageID);
        m_clientIDs.get(clientID).channel.writeAndFlush(pubRecMessage);
    }

    private void sendPubAck(String clientId, int messageID) {
        LOG.trace("sendPubAck invoked");
        PubAckMessage pubAckMessage = new PubAckMessage();
        pubAckMessage.setMessageID(messageID);

        try {
            if (m_clientIDs == null) {
                throw new RuntimeException("Internal bad error, found m_clientIDs to null while it should be initialized, somewhere it's overwritten!!");
            }
            LOG.debug("clientIDs are {}", m_clientIDs);
            if (m_clientIDs.get(clientId) == null) {
                throw new RuntimeException(String.format("Can't find a ConnectionDescriptor for client %s in cache %s", clientId, m_clientIDs));
            }
            m_clientIDs.get(clientId).channel.writeAndFlush(pubAckMessage);
        } catch(Throwable t) {
            LOG.error(null, t);
        }
    }

    /**
     * Second phase of a publish QoS2 protocol, sent by publisher to the broker. Search the stored message and publish
     * to all interested subscribers.
     * */
    public void processPubRel(Channel channel, PubRelMessage msg) {
        LOG.debug("allowedQos is {}", allowedQos);
        if (allowedQos != null && QOSType.EXACTLY_ONCE != allowedQos) {
            sendPubComp(NettyUtils.clientID(channel), msg.getMessageID());
            return;
        }

        String clientID = NettyUtils.clientID(channel);
        int messageID = msg.getMessageID();
        LOG.debug("PUB --PUBREL--> SRV processPubRel invoked for clientID {} ad messageID {}", clientID, messageID);
        ClientSession targetSession = m_sessionsStore.sessionForClient(clientID);
        verifyToActivate(clientID, targetSession);
        IMessagesStore.StoredMessage evt = targetSession.storedMessage(messageID);
        route2Subscribers(evt);

        if (evt.isRetained()) {
            final String topic = evt.getTopic();
            if (!evt.getMessage().hasRemaining()) {
                m_messagesStore.cleanRetained(topic);
            } else {
                m_messagesStore.storeRetained(topic, evt.getGuid());
            }
        }

        sendPubComp(clientID, messageID);
    }

    private void sendPubComp(String clientID, int messageID) {
        LOG.debug("PUB <--PUBCOMP-- SRV sendPubComp invoked for clientID {} ad messageID {}", clientID, messageID);
        PubCompMessage pubCompMessage = new PubCompMessage();
        pubCompMessage.setMessageID(messageID);

        m_clientIDs.get(clientID).channel.writeAndFlush(pubCompMessage);
    }

    public void processPubRec(Channel channel, PubRecMessage msg) {
        String clientID = NettyUtils.clientID(channel);
        int messageID = msg.getMessageID();
        ClientSession targetSession = m_sessionsStore.sessionForClient(clientID);
        verifyToActivate(clientID, targetSession);
        //remove from the inflight and move to the QoS2 second phase queue
        targetSession.inFlightAcknowledged(messageID);
        targetSession.secondPhaseAckWaiting(messageID);
        //once received a PUBREC reply with a PUBREL(messageID)
        LOG.debug("\t\tSRV <--PUBREC-- SUB processPubRec invoked for clientID {} ad messageID {}", clientID, messageID);
        PubRelMessage pubRelMessage = new PubRelMessage();
        pubRelMessage.setMessageID(messageID);
        pubRelMessage.setQos(AbstractMessage.QOSType.LEAST_ONE);

        channel.writeAndFlush(pubRelMessage);
    }

    public void processPubComp(Channel channel, PubCompMessage msg) {
        String clientID = NettyUtils.clientID(channel);
        int messageID = msg.getMessageID();
        LOG.debug("\t\tSRV <--PUBCOMP-- SUB processPubComp invoked for clientID {} ad messageID {}", clientID, messageID);
        //once received the PUBCOMP then remove the message from the temp memory
        ClientSession targetSession = m_sessionsStore.sessionForClient(clientID);
        verifyToActivate(clientID, targetSession);
        targetSession.secondPhaseAcknowledged(messageID);
    }

    public void processDisconnect(Channel channel) throws InterruptedException {
        String clientID = NettyUtils.clientID(channel);
        boolean cleanSession = NettyUtils.cleanSession(channel);
        LOG.info("DISCONNECT client <{}> with clean session {}", clientID, cleanSession);
        ClientSession clientSession = m_sessionsStore.sessionForClient(clientID);
        clientSession.disconnect();

        m_clientIDs.remove(clientID);
        channel.close();

        //cleanup the will store
        m_willStore.remove(clientID);

        m_interceptor.notifyClientDisconnected(clientID, channel.hashCode());
        LOG.info("DISCONNECT client <{}> finished", clientID, cleanSession);
    }

    public void processConnectionLost(String clientID, boolean sessionStolen, Channel channel) {
        m_interceptor.notifyConnectionLost(clientID, channel.hashCode());

        ConnectionDescriptor oldConnDescr = new ConnectionDescriptor(clientID, channel, true);
        m_clientIDs.remove(clientID, oldConnDescr);
        //If already removed a disconnect message was already processed for this clientID
        if (sessionStolen) {
            //de-activate the subscriptions for this ClientID
            ClientSession clientSession = m_sessionsStore.sessionForClient(clientID);
            clientSession.deactivate();
            LOG.info("Lost connection with client <{}>", clientID);
        }
        //publish the Will message (if any) for the clientID
        if (!sessionStolen && m_willStore.containsKey(clientID)) {
            WillMessage will = m_willStore.get(clientID);
            forwardPublishWill(will, clientID);
            m_willStore.remove(clientID);
        }
    }

    /**
     * Remove the clientID from topic subscription, if not previously subscribed,
     * doesn't reply any error
     */
    public void processUnsubscribe(Channel channel, UnsubscribeMessage msg) {
        List<String> topics = msg.topicFilters();
        int messageID = msg.getMessageID();
        String clientID = NettyUtils.clientID(channel);

        LOG.debug("UNSUBSCRIBE subscription on topics {} for clientID <{}>", topics, clientID);

        ClientSession clientSession = m_sessionsStore.sessionForClient(clientID);
        verifyToActivate(clientID, clientSession);
        for (String topic : topics) {
            boolean validTopic = SubscriptionsStore.validate(topic);
            if (!validTopic) {
                //close the connection, not valid topicFilter is a protocol violation
                channel.close();
                LOG.warn("UNSUBSCRIBE found an invalid topic filter <{}> for clientID <{}>", topic, clientID);
                return;
            }

            subscriptions.removeSubscription(topic, clientID);
            clientSession.unsubscribeFrom(topic);
            m_interceptor.notifyTopicUnsubscribed(topic, clientID);
        }

        //ack the client
        UnsubAckMessage ackMessage = new UnsubAckMessage();
        ackMessage.setMessageID(messageID);

        LOG.info("replying with UnsubAck to MSG ID {}", messageID);
        channel.writeAndFlush(ackMessage);
    }

    public void processSubscribe(Channel channel, SubscribeMessage msg) {
        String clientID = NettyUtils.clientID(channel);
        LOG.debug("SUBSCRIBE client <{}> packetID {}", clientID, msg.getMessageID());
        //ack the client
        SubAckMessage ackMessage = new SubAckMessage();
        ackMessage.setMessageID(msg.getMessageID());
        ackMessage.addType(msg.getQos());
        channel.writeAndFlush(ackMessage);
        return;
    }

    private boolean subscribeSingleTopic(final Subscription newSubscription) {
        subscriptions.add(newSubscription.asClientTopicCouple());

        //scans retained messages to be published to the new subscription
        //TODO this is ugly, it does a linear scan on potential big dataset
        Collection<IMessagesStore.StoredMessage> messages = m_messagesStore.searchMatching(new IMatchingCondition() {
            public boolean match(String key) {
                return SubscriptionsStore.matchTopics(key, newSubscription.getTopicFilter());
            }
        });

        ClientSession targetSession = m_sessionsStore.sessionForClient(newSubscription.getClientId());
        verifyToActivate(newSubscription.getClientId(), targetSession);
        for (IMessagesStore.StoredMessage storedMsg : messages) {
            //fire the as retained the message
            LOG.debug("send publish message for topic {}", newSubscription.getTopicFilter());
            //forwardPublishQoS0(newSubscription.getClientId(), storedMsg.getTopic(), storedMsg.getQos(), storedMsg.getPayload(), true);
            Integer packetID = storedMsg.getQos() == QOSType.MOST_ONE ? null :
                    targetSession.nextPacketId();
            directSend(targetSession, storedMsg.getTopic(), storedMsg.getQos(), storedMsg.getPayload(), true, packetID);
        }

        //notify the Observables
        m_interceptor.notifyTopicSubscribed(newSubscription);
        return true;
    }
}
