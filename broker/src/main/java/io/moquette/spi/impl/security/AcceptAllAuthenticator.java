package io.moquette.spi.impl.security;

import io.moquette.proto.messages.ConnectMessage;
import io.moquette.spi.security.IAuthenticator;

/**
 * Created by andrea on 8/23/14.
 */
public class AcceptAllAuthenticator implements IAuthenticator {
    public boolean checkValid(String username, byte[] password) {
        return true;
    }

    @Override
    public byte checkValid(ConnectMessage message, int socketChannelHashCode, boolean b) {
        // Not implemented
        return 0;
    }
}
