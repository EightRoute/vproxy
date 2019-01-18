package net.cassite.vproxy.example;

import net.cassite.vproxy.connection.NetEventLoop;
import net.cassite.vproxy.connection.Server;
import net.cassite.vproxy.proxy.Proxy;
import net.cassite.vproxy.proxy.ProxyEventHandler;
import net.cassite.vproxy.proxy.ProxyNetConfig;
import net.cassite.vproxy.selector.SelectorEventLoop;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

// create an echo server, and create a proxy
// client requests proxy, proxy requests echo server
public class ProxyEchoServer {
    public static void main(String[] args) throws IOException, InterruptedException {
        // start echo server
        SelectorEventLoop selectorEventLoop = SelectorEventLoopEchoServer.createServer(19083);

        // create event loop (just use the returned event loop)
        NetEventLoop netEventLoop = new NetEventLoop(selectorEventLoop);
        // create server
        ServerSocketChannel channel = ServerSocketChannel.open();
        channel.bind(new InetSocketAddress(18083));
        Server server = new Server(channel);
        // init config
        ProxyNetConfig config = new ProxyNetConfig()
            .setAcceptLoop(netEventLoop)
            .setConnGen(conn -> {
                // connect to localhost 18084
                SocketChannel s = SocketChannel.open();
                s.configureBlocking(false);
                s.connect(new InetSocketAddress("127.0.0.1", 19083));
                return s;
            })
            .setHandleLoopProvider(() -> netEventLoop) // use same event loop as the acceptor for demonstration
            .setServer(server)
            .setInBufferSize(8) // make it small to see how it acts when read buffer is full
            .setOutBufferSize(4); // make it even smaller to see how it acts when write buffer is full
        // create proxy and start
        Proxy proxy = new Proxy(config, new MyProxyEventHandler());
        proxy.handle();
        new Thread(selectorEventLoop::loop).start();

        Thread.sleep(500);
        EchoClient.runBlock(18083);
        selectorEventLoop.close();
    }
}

class MyProxyEventHandler implements ProxyEventHandler {
    @Override
    public void serverRemoved(Server server) {
        server.close();
    }
}