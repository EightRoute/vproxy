package net.cassite.vproxy.component.check;

import net.cassite.vproxy.connection.*;
import net.cassite.vproxy.selector.TimerEvent;
import net.cassite.vproxy.util.Callback;
import net.cassite.vproxy.util.LogType;
import net.cassite.vproxy.util.Logger;
import net.cassite.vproxy.util.RingBuffer;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.InterruptedByTimeoutException;
import java.nio.channels.SocketChannel;

// connect to target address then close the connection
// it's useful when running health check
public class ConnectClient {
    class ConnectClientConnectionHandler implements ClientConnectionHandler {
        private final Callback<Void, IOException> callback;
        private final TimerEvent timerEvent;

        ConnectClientConnectionHandler(Callback<Void, IOException> callback, TimerEvent timerEvent) {
            this.callback = callback;
            this.timerEvent = timerEvent;
        }

        @SuppressWarnings("unchecked")
        @Override
        public void connected(ClientConnectionHandlerContext ctx) {
            timerEvent.cancel(); // cancel timer if possible

            if (callback.isCalled()) // already called by timer
                return;
            if (!stopped) callback.succeeded(null);
            ctx.connection.close(); // close the connection
        }

        @Override
        public void readable(ConnectionHandlerContext ctx) {
            // will never fire
        }

        @Override
        public void writable(ConnectionHandlerContext ctx) {
            // will never fire
        }

        @SuppressWarnings("unchecked")
        @Override
        public void exception(ConnectionHandlerContext ctx, IOException err) {
            timerEvent.cancel(); // cancel timer if possible

            assert Logger.lowLevelDebug("exception when doing health check, conn = " + ctx.connection + ", err = " + err);

            if (callback.isCalled()) // already called by timer
                return;
            if (!stopped) callback.failed(err);
            ctx.connection.close(); // close the connection
        }

        @Override
        public void closed(ConnectionHandlerContext ctx) {
            // will never fire since we do not read or write
        }

        @Override
        public void removed(ConnectionHandlerContext ctx) {
            ctx.connection.close();
        }
    }

    public final NetEventLoop eventLoop;
    public final SocketAddress remote;
    public final int timeout;
    private boolean stopped = false;

    public ConnectClient(NetEventLoop eventLoop, SocketAddress remote, int timeout) {
        this.eventLoop = eventLoop;
        this.remote = remote;
        this.timeout = timeout;
    }

    public void handle(Callback<Void, IOException> cb) {
        SocketChannel channel;
        try {
            channel = SocketChannel.open();
        } catch (IOException e) {
            // create channel failed, maybe reaches os fd limit, it's an unexpected condition
            Logger.fatal(LogType.UNEXPECTED, "create socket channel failed " + e);
            if (!stopped) cb.failed(e);
            return;
        }
        // connect to remote
        ClientConnection conn;
        try {
            channel.configureBlocking(false);
            channel.connect(remote);
            conn = new ClientConnection(channel,
                // i/o buffer is not useful at all here
                RingBuffer.allocate(0), RingBuffer.allocate(0));
        } catch (IOException e) {
            if (!stopped) cb.failed(e);
            return;
        }
        // create a timer handling the connecting timeout
        TimerEvent timer = eventLoop.selectorEventLoop.delay(timeout, () -> {
            assert Logger.lowLevelDebug("timeout when doing health check " + conn);
            conn.close();
            if (!stopped) cb.failed(new InterruptedByTimeoutException());
        });
        try {
            eventLoop.addClientConnection(conn, null, new ConnectClientConnectionHandler(cb, timer));
        } catch (IOException e) {
            if (!stopped) cb.failed(e);
            // exception occurred, so ignore timeout
            timer.cancel();
        }
    }

    public void stop() {
        stopped = true;
    }
}
