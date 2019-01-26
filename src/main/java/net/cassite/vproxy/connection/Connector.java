package net.cassite.vproxy.connection;

import net.cassite.vproxy.util.RingBuffer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;

public class Connector {
    private final InetSocketAddress remote;
    private final InetSocketAddress local;

    public Connector(InetSocketAddress remote, InetSocketAddress local) {
        this.remote = remote;
        this.local = local;
    }

    public Connector(InetSocketAddress remote, InetAddress local) {
        this(remote, new InetSocketAddress(local, 0));
    }

    public ClientConnection connect(RingBuffer in, RingBuffer out) throws IOException {
        return ClientConnection.create(remote, local, in, out);
    }

    @Override
    public String toString() {
        return "Connector(remote(" + remote + "), local(" + local + "))";
    }
}
