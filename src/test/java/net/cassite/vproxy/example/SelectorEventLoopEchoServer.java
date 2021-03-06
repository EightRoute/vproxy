package net.cassite.vproxy.example;

import net.cassite.vproxy.selector.Handler;
import net.cassite.vproxy.selector.HandlerContext;
import net.cassite.vproxy.selector.SelectorEventLoop;
import net.cassite.vproxy.util.RingBuffer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

// this example shows how to create a echo server with classes defined in `selector` package
public class SelectorEventLoopEchoServer {
    public static void main(String[] args) throws IOException, InterruptedException {
        SelectorEventLoop eventLoop = createServer(18080);
        // start loop in another thread
        new Thread(eventLoop::loop, "EventLoopThread").start();

        Thread.sleep(500);
        AlphabetBlockingClient.runBlock(18080, 10, false);
        eventLoop.close();
    }

    static SelectorEventLoop createServer(int port) throws IOException {
        // create a event loop object
        SelectorEventLoop eventLoop = SelectorEventLoop.open();
        // create a server socket
        ServerSocketChannel server = ServerSocketChannel.open();
        // bind it to local address
        server.bind(new InetSocketAddress(port));
        // add it to event loop
        eventLoop.add(server, SelectionKey.OP_ACCEPT, null, new ServerHandler());

        return eventLoop;
    }
}

class ServerHandler implements Handler<ServerSocketChannel> {
    @Override
    public void accept(HandlerContext<ServerSocketChannel> ctx) {
        final SocketChannel client;
        try {
            client = ctx.getChannel().accept();
        } catch (IOException e) {
            // error occurred, remove from event loop
            ctx.remove();
            return;
        }
        try {
            ctx.getEventLoop().add(client, SelectionKey.OP_READ, null, new ClientHandler());
        } catch (IOException e) {
            // error for adding this client
            // close the client
            try {
                client.close();
            } catch (IOException e1) {
                // we can do nothing about it
            }
        }
    }

    @Override
    public void connected(HandlerContext<ServerSocketChannel> ctx) {
        // should not fire
    }

    @Override
    public void readable(HandlerContext<ServerSocketChannel> ctx) {
        // should not fire
    }

    @Override
    public void writable(HandlerContext<ServerSocketChannel> ctx) {
        // should not fire
    }

    @Override
    public void removed(HandlerContext<ServerSocketChannel> ctx) {
        // removed from loop, let's close it
        ServerSocketChannel svr = ctx.getChannel();
        try {
            svr.close();
        } catch (IOException e) {
            // we can do nothing about it
        }
        System.err.println("echo server closed");
    }
}

class ClientHandler implements Handler<SocketChannel> {
    private final RingBuffer buffer = RingBuffer.allocateDirect(8); // let's set this very small, to test all the code flow

    @Override
    public void accept(HandlerContext<SocketChannel> ctx) {
        // should not fire
    }

    @Override
    public void connected(HandlerContext<SocketChannel> ctx) {
        // should not fire
    }

    @Override
    public void readable(HandlerContext<SocketChannel> ctx) {
        int readBytes;
        try {
            readBytes = buffer.storeBytesFrom(ctx.getChannel());
        } catch (IOException e) {
            // error occurred on the socket
            // remove from loop and close it
            ctx.remove();
            try {
                ctx.getChannel().close();
            } catch (IOException e1) {
                // we can do nothing about it, just ignore
            }
            return;
        }
        if (readBytes == 0) {
            // cannot read any data
            if (buffer.free() == 0) {
                // reached limit
                // remove read event and add write event
                ctx.modify((ctx.getOps() & ~SelectionKey.OP_READ) | SelectionKey.OP_WRITE);
                // let's print this in std out, otherwise we cannot see the output being separated
                System.out.println("\033[0;36mbuffer is full, let's stop reading and start writing\033[0m");
            }
        } else if (readBytes < 0) {
            // remote write is closed
            // we just ignore the remote read and close the connection
            System.err.println("connection closed");
            ctx.remove();
            try {
                ctx.getChannel().close();
            } catch (IOException e) {
                // we can do nothing about it, just ignore
            }
        } else {
            // print to console
            System.out.println("buffer now looks like: " + buffer);
            // add write event
            ctx.modify(ctx.getOps() | SelectionKey.OP_WRITE);
        }
    }

    @Override
    public void writable(HandlerContext<SocketChannel> ctx) {
        final int writeBytes;
        try {
            // maxBytesToWrite is set to a very strange number 3
            // to demonstrate how it operates when buffer is almost full
            writeBytes = buffer.writeTo(ctx.getChannel(), 3);
            // you cloud simply use buffer.writeTo(ctx.getChannel())
        } catch (IOException e) {
            // error occurred
            // remove the channel
            ctx.remove();
            try {
                ctx.getChannel().close();
            } catch (IOException e1) {
                // we can do nothing about it
            }
            return;
        }
        int oldOps = ctx.getOps();
        int ops = oldOps;
        if (writeBytes > 0) {
            // buffer definitely has some space left now
            if ((ops & SelectionKey.OP_READ) == 0) {
                System.out.println("\033[0;32mbuffer now has some free space, let's start reading\033[0m");
            }
            ops |= SelectionKey.OP_READ;
        }
        if (buffer.used() == 0) {
            // nothing to write anymore
            ops &= ~SelectionKey.OP_WRITE;
            System.out.println("\033[0;32mnothing to write for now, let's stop writing\033[0m");
        }
        if (oldOps != ops) {
            ctx.modify(ops);
        }
    }

    @Override
    public void removed(HandlerContext<SocketChannel> ctx) {
        // close the connection here
        try {
            ctx.getChannel().close();
        } catch (IOException e) {
            // we can do nothing about it
            e.printStackTrace();
        }
    }
}
