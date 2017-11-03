package io.moquette.spi;

public interface IPersistentStore {
    void initStore();

    void close();

    IMessagesStore messagesStore();

    ISessionsStore sessionsStore(IMessagesStore msgStore);
}
