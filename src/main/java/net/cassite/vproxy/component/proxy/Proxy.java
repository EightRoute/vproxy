package net.cassite.vproxy.component.proxy;

import net.cassite.vproxy.connection.*;
import net.cassite.vproxy.util.LogType;
import net.cassite.vproxy.util.Logger;
import net.cassite.vproxy.util.RingBuffer;
import net.cassite.vproxy.util.Tuple;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;

/**
 * when a connection is accepted, another connection will be generated by calling the callback handler<br>
 * the accepted connection and the new connection form up a {@link Session}<br>
 * the session operations will always be handled in the same event loop
 */
public class Proxy {
    private static void utilValidate(ProxyNetConfig config) {
        if (config.acceptLoop == null)
            throw new IllegalArgumentException("no accept loop");
        if (config.connGen == null)
            throw new IllegalArgumentException("no connection generator");
        if (config.handleLoopProvider == null)
            throw new IllegalArgumentException("no handler loop provider");
        if (config.server == null)
            throw new IllegalArgumentException("no server");
        if (config.inBufferSize <= 0)
            throw new IllegalArgumentException("inBufferSize <= 0");
        if (config.outBufferSize <= 0)
            throw new IllegalArgumentException("outBufferSize <= 0");
    }

    private static void utilCloseConnection(Connection connection) {
        assert Logger.lowLevelDebug("close connection " + connection);
        connection.close();
    }

    private static void utilCloseConnectionAndReleaseBuffers(Connection connection) {
        utilCloseConnection(connection);
        connection.inBuffer.clean();
        connection.outBuffer.clean();
    }

    private static void utilCloseSessionAndReleaseBuffers(Session session) {
        utilCloseConnectionAndReleaseBuffers(session.active);
        utilCloseConnection(session.passive);
    }

    class SessionServerHandler implements ServerHandler {
        @Override
        public void acceptFail(ServerHandlerContext ctx, IOException err) {
            Logger.fatal(LogType.SERVER_ACCEPT_FAIL, "accept connection failed, server = " + config.server + ", err = " + err);
        }

        @Override
        public void connection(ServerHandlerContext ctx, Connection connection) {
            // make connection to another end point
            Tuple<InetSocketAddress, InetSocketAddress> remoteLocal = config.connGen.genRemoteLocal(connection);

            // check whether address tuple is null
            // null means the user code fail to provide a new connection
            // maybe user think that the backend is not working, or the source ip is forbidden
            // any way, the user refuse to provide a new connection
            if (remoteLocal == null) {
                Logger.info(LogType.NO_CLIENT_CONN, "the user code refuse to provide a remote endpoint");
                // close the active connection
                utilCloseConnectionAndReleaseBuffers(connection);
                return;
            }

            ClientConnection clientConnection;
            try {
                clientConnection = ClientConnection.create(remoteLocal.left, remoteLocal.right,
                    // switch the two buffers to make a PROXY
                    connection.outBuffer, connection.inBuffer);
            } catch (IOException e) {
                Logger.fatal(LogType.CONN_ERROR, "create passive connection object failed, maybe provided endpoint info is invalid: " + e);
                // it should not happen if user provided endpoint is valid
                // but if it happens, we close both sides

                utilCloseConnectionAndReleaseBuffers(connection);
                return;
            }

            Session session = new Session(connection, clientConnection);
            ClientConnectionHandler handler = new SessionClientConnectionHandler(session);

            // we get a new event loop for handling
            // the event loop is provided by user
            // user may use the same loop as the acceptLoop
            //
            // and we only register the passive connection here
            // the active connection will be registered
            // when the passive connection is successfully established
            NetEventLoop loop = config.handleLoopProvider.get();
            if (loop == null) {
                // the loop not exist
                utilCloseSessionAndReleaseBuffers(session);
                Logger.warn(LogType.NO_EVENT_LOOP, "cannot get event loop for client connection " + clientConnection);
                return;
            }
            try {
                loop.addClientConnection(clientConnection, null, handler);
            } catch (IOException e) {
                Logger.fatal(LogType.EVENT_LOOP_ADD_FAIL, "register passive connection into event loop failed, passive conn = " + clientConnection + ", err = " + e);
                // should not happen
                // but if it happens, we close both sides
                utilCloseSessionAndReleaseBuffers(session);
            }
        }

        @Override
        public Tuple<RingBuffer, RingBuffer> getIOBuffers(SocketChannel channel) {
            RingBuffer inBuffer = RingBuffer.allocateDirect(config.inBufferSize);
            RingBuffer outBuffer = RingBuffer.allocateDirect(config.outBufferSize);
            return new Tuple<>(inBuffer, outBuffer);
        }

        @Override
        public void removed(ServerHandlerContext ctx) {
            handler.serverRemoved(ctx.server);
        }
    }

    class SessionConnectionHandler implements ConnectionHandler {
        private final Session session;

        SessionConnectionHandler(Session session) {
            this.session = session;
        }

        @Override
        public void readable(ConnectionHandlerContext ctx) {
            // the input buffer is attached to remote write buffer
            // and output buffer is attached to remote read buffer
            // as a result,
            // the write and read process is automatically handled by the lib
        }

        @Override
        public void writable(ConnectionHandlerContext ctx) {
            // we might write the last bytes here
            // when we write everything, we close the connection
            if (session.passive.isClosed() && ctx.connection.outBuffer.used() == 0)
                utilCloseConnectionAndReleaseBuffers(ctx.connection);
        }

        @Override
        public void exception(ConnectionHandlerContext ctx, IOException err) {
            Logger.error(LogType.CONN_ERROR, "session got exception: " + err);
            // close both sides
            utilCloseSessionAndReleaseBuffers(session);
        }

        @Override
        public void closed(ConnectionHandlerContext ctx) {
            assert Logger.lowLevelDebug("now the connection is closed, we should close the session");
            // now the active connection is closed
            if (session.isClosed()) // do nothing if the session is already closed
                return;
            if (session.passive.outBuffer.used() == 0) {
                // nothing to write anymore
                // close the passive connection
                assert Logger.lowLevelDebug("nothing to write for passive connection, do close");
                utilCloseConnectionAndReleaseBuffers(session.passive);
            } else {
                assert Logger.lowLevelDebug("we should close the passive connection after everything wrote");
                // and we close the active conn's output buffer, i.e. passive's input buffer
                // then the passive will not be able to write anything to active

                // the passive can still read from the active conn's in-buffer if still got some bytes
                session.passive.inBuffer.close();
            }
        }

        @Override
        public void removed(ConnectionHandlerContext ctx) {
            utilCloseSessionAndReleaseBuffers(session);
        }
    }

    class SessionClientConnectionHandler implements ClientConnectionHandler {
        private final Session session;

        SessionClientConnectionHandler(Session session) {
            this.session = session;
        }

        @Override
        public void connected(ClientConnectionHandlerContext ctx) {
            assert Logger.lowLevelDebug("passive connection established: " + ctx.connection);

            // now we can add active connection into event loop
            // use event loop from context
            // the active and passive connection are handled in the same loop
            try {
                ctx.eventLoop.addConnection(session.active, null, new SessionConnectionHandler(session));
            } catch (IOException e) {
                Logger.fatal(LogType.EVENT_LOOP_ADD_FAIL, "register active connection into event loop failed, conn = " + session.active + ", err = " + e);
                // add into event loop failed
                // close session
                assert Logger.lowLevelDebug("nothing to write for active connection, do close");
                utilCloseSessionAndReleaseBuffers(session);
            }
        }

        @Override
        public void readable(ConnectionHandlerContext ctx) {
            // see readable in SessionConnectHandler#readable
        }

        @Override
        public void writable(ConnectionHandlerContext ctx) {
            // we might write the last bytes here
            // when we write everyhing, we close the connection
            if (session.active.isClosed() && ctx.connection.outBuffer.used() == 0)
                utilCloseConnectionAndReleaseBuffers(ctx.connection);
        }

        @Override
        public void exception(ConnectionHandlerContext ctx, IOException err) {
            Logger.error(LogType.CONN_ERROR, "session got exception: " + err);
            // close both sides
            utilCloseSessionAndReleaseBuffers(session);
        }

        @Override
        public void closed(ConnectionHandlerContext ctx) {
            assert Logger.lowLevelDebug("now the passive connection is closed, we should close the session");
            // now the passive connection is closed
            if (session.isClosed()) // do nothing if the session is already closed
                return;
            if (session.active.outBuffer.used() == 0) {
                // nothing to write anymore
                // close the active connection
                utilCloseConnectionAndReleaseBuffers(session.active);
            } else {
                assert Logger.lowLevelDebug("we should close the active connection after everything wrote");
                // and we close the passive conn's output buffer, i.e. active's input buffer
                // then the active will not be able to write anything to passive

                // the active can still read from the passive conn's in-buffer if still got some bytes
                session.active.inBuffer.close();
            }
        }

        @Override
        public void removed(ConnectionHandlerContext ctx) {
            utilCloseSessionAndReleaseBuffers(session);
        }
    }

    private final ProxyNetConfig config;
    private final ProxyEventHandler handler;

    public Proxy(ProxyNetConfig config, ProxyEventHandler handler) {
        this.handler = handler;
        this.config = config;
    }

    public void handle() throws IOException {
        utilValidate(config);
        config.acceptLoop.addServer(config.server, null, new SessionServerHandler());
    }

    public void stop() {
        config.acceptLoop.removeServer(config.server);
    }
}
