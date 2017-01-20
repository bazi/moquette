package io.moquette.interception.messages;

/**
 * @author Wagner Macedo
 */
public class InterceptConnectionLostMessage {
    private final String clientID;

    public InterceptConnectionLostMessage(String clientID) {
        this.clientID = clientID;
    }

    public String getClientID() {
        return clientID;
    }
}
