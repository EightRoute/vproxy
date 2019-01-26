package net.cassite.vproxy.component.check;

import net.cassite.vproxy.connection.*;
import net.cassite.vproxy.selector.TimerEvent;
import net.cassite.vproxy.util.Callback;
import net.cassite.vproxy.util.Logger;
import net.cassite.vproxy.util.RingBuffer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.InterruptedByTimeoutException;

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
            ctx.connection.close(); // close the connection

            if (!callback.isCalled() /*already called by timer*/ && !stopped) callback.succeeded(null);
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
            ctx.connection.close(); // close the connection

            assert Logger.lowLevelDebug("exception when doing health check, conn = " + ctx.connection + ", err = " + err);

            if (!callback.isCalled() /*already called by timer*/ && !stopped) callback.failed(err);
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
    public final InetSocketAddress remote;
    public final InetAddress local;
    public final int timeout;
    private boolean stopped = false;

    public ConnectClient(NetEventLoop eventLoop,
                         InetSocketAddress remote,
                         InetAddress local,
                         int timeout) {
        this.eventLoop = eventLoop;
        this.remote = remote;
        this.local = local;
        this.timeout = timeout;
    }

    public void handle(Callback<Void, IOException> cb) {
        // connect to remote
        ClientConnection conn;
        try {
            conn = ClientConnection.create(remote, local,
                // i/o buffer is not useful at all here
                RingBuffer.allocate(0), RingBuffer.allocate(0));
        } catch (IOException e) {
            if (!stopped) cb.failed(e);
            return;
        }
        // create a timer handling the connecting timeout
        TimerEvent timer = eventLoop.getSelectorEventLoop().delay(timeout, () -> {
            assert Logger.lowLevelDebug("timeout when doing health check " + conn);
            conn.close();
            if (!cb.isCalled() /*called by connection*/ && !stopped) cb.failed(new InterruptedByTimeoutException());
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
