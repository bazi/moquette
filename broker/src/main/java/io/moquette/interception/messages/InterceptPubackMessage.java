package io.moquette.interception.messages;

import io.moquette.proto.messages.PubAckMessage;

import java.nio.ByteBuffer;

/**
 * @author Wagner Macedo
 */
public class InterceptPubackMessage extends InterceptAbstractMessage {
    private final PubAckMessage msg;
    private final String clientID;

    public InterceptPubackMessage(PubAckMessage msg, String clientID) {
        super(msg);
        this.msg = msg;
        this.clientID = clientID;
    }

    public Integer getMessageID() {
        return msg.getMessageID();
    }

    public String getClientID() {
        return clientID;
    }
}
