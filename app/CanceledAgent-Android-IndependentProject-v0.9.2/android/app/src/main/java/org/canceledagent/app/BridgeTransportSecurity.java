package org.canceledagent.app;

import java.io.IOException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** JSSE hostname-bound TLS connection for the optional outbound relay. */
public final class BridgeTransportSecurity {
    private BridgeTransportSecurity() {}
    public static SSLSocket connect(SSLSocketFactory factory, String host, int port, int timeoutMs)
            throws IOException {
        if (host == null || host.isEmpty() || port < 1 || port > 65535) throw new IOException("Invalid relay origin");
        SSLSocket socket = (SSLSocket) factory.createSocket(host, port);
        try {
            SSLParameters params = socket.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(params);
            socket.setSoTimeout(timeoutMs);
            socket.startHandshake();
            return socket;
        } catch (IOException | RuntimeException ex) {
            try { socket.close(); } catch (IOException ignored) {}
            throw ex;
        }
    }
}
