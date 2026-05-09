package com.winlator.alsaserver;

import com.winlator.xconnector.Client;
import com.winlator.xconnector.ConnectionHandler;

public class ALSAClientConnectionHandler implements ConnectionHandler {
    private final String containerVariant;

    public ALSAClientConnectionHandler(String containerVariant) {
        this.containerVariant = containerVariant;
    }

    @Override
    public void handleNewConnection(Client client) {
        client.createIOStreams();
        client.setTag(new ALSAClient(this.containerVariant));
    }

    @Override
    public void handleConnectionShutdown(Client client) {
        ((ALSAClient)client.getTag()).release();
    }
}
