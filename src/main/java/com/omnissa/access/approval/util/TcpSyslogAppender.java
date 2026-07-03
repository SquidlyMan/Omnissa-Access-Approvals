package com.omnissa.access.approval.util;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * TCP (optionally TLS) syslog appender. Logback's built-in SyslogAppender is
 * UDP-only; this appender ships RFC 3164-style lines over a persistent TCP
 * socket using newline-delimited framing, which rsyslog and Graylog accept.
 *
 * Failure handling never blocks the application: connects use a 5 s timeout,
 * the socket read timeout is 5 s, a failed write drops the event, and
 * reconnection is attempted lazily on the next event (no retry loop). One
 * WARN is emitted per outage via the logback status manager.
 *
 * With {@code useTls}, an SSLContext is built once at {@link #start()}:
 * a provided CA PEM replaces the platform trust store, and a client
 * certificate + unencrypted PKCS#8 key enable mutual TLS. File-path variants
 * of the PEM material take precedence over the inline variants.
 */
public class TcpSyslogAppender extends AppenderBase<ILoggingEvent> {

    private static final int SOCKET_TIMEOUT_MILLIS = 5000;
    private static final int FACILITY_USER = 1;
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("MMM dd HH:mm:ss", Locale.ENGLISH);

    private String host;
    private int port;
    private boolean useTls;

    // Optional TLS material (see class javadoc). All may stay blank.
    private String clientCertPem = "";
    private String clientKeyPem = "";
    private String caPem = "";
    private String clientCertFile = "";
    private String clientKeyFile = "";
    private String caFile = "";

    private SSLContext sslContext;
    private Socket socket;
    private OutputStream out;
    private boolean connectionWarned;

    public void setHost(String host) {
        this.host = host;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public void setUseTls(boolean useTls) {
        this.useTls = useTls;
    }

    public void setClientCertPem(String clientCertPem) {
        this.clientCertPem = clientCertPem;
    }

    public void setClientKeyPem(String clientKeyPem) {
        this.clientKeyPem = clientKeyPem;
    }

    public void setCaPem(String caPem) {
        this.caPem = caPem;
    }

    public void setClientCertFile(String clientCertFile) {
        this.clientCertFile = clientCertFile;
    }

    public void setClientKeyFile(String clientKeyFile) {
        this.clientKeyFile = clientKeyFile;
    }

    public void setCaFile(String caFile) {
        this.caFile = caFile;
    }

    @Override
    public void start() {
        if (host == null || host.isBlank()) {
            addError("TcpSyslogAppender requires a host");
            return;
        }
        if (useTls) {
            try {
                sslContext = buildSslContext();
            } catch (Exception e) {
                addError("Failed to initialize TLS for syslog forwarding: " + e.getMessage(), e);
                return;
            }
            if (sslContext == null) {
                return; // configuration error already reported via addError
            }
        }
        super.start();
    }

    @Override
    protected synchronized void append(ILoggingEvent event) {
        byte[] line = format(event).getBytes(StandardCharsets.UTF_8);
        try {
            ensureConnected();
            out.write(line);
            out.flush();
        } catch (IOException e) {
            // Drop the event, close the socket, and reconnect lazily on the
            // NEXT event — never retry-loop or block the application.
            closeQuietly();
            if (!connectionWarned) {
                addWarn("Syslog TCP delivery to " + host + ":" + port + " failed ("
                        + e.getMessage() + "); dropping events until the connection recovers", e);
                connectionWarned = true;
            }
        }
    }

    @Override
    public synchronized void stop() {
        closeQuietly();
        super.stop();
    }

    private void ensureConnected() throws IOException {
        if (socket != null && !socket.isClosed()) {
            return;
        }
        Socket plain = new Socket();
        try {
            plain.connect(new InetSocketAddress(host, port), SOCKET_TIMEOUT_MILLIS);
            plain.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            if (useTls) {
                SSLSocket tls = (SSLSocket) sslContext.getSocketFactory()
                        .createSocket(plain, host, port, true);
                tls.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
                tls.startHandshake();
                socket = tls;
            } else {
                socket = plain;
            }
            out = socket.getOutputStream();
            connectionWarned = false;
        } catch (IOException e) {
            try {
                plain.close();
            } catch (IOException ignored) {
                // best-effort cleanup
            }
            throw e;
        }
    }

    private void closeQuietly() {
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
                // best-effort cleanup
            }
            out = null;
        }
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // best-effort cleanup
            }
            socket = null;
        }
    }

    /**
     * RFC 3164-style line: {@code <PRI>MMM dd HH:mm:ss omnissa-approvals:
     * [thread] logger message\n}. PRI = USER(1)*8 + severity.
     */
    private String format(ILoggingEvent event) {
        int pri = FACILITY_USER * 8 + severity(event.getLevel());
        String timestamp = TIMESTAMP_FORMAT.format(ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(event.getTimeStamp()), ZoneId.systemDefault()));
        String message = event.getFormattedMessage();
        if (message == null) {
            message = "";
        }
        // Newline-delimited framing: embedded newlines would split one event
        // into several syslog records, so flatten them.
        message = message.replace("\r", "").replace('\n', ' ');
        return "<" + pri + ">" + timestamp + " omnissa-approvals: ["
                + event.getThreadName() + "] " + event.getLoggerName() + " " + message + "\n";
    }

    private int severity(Level level) {
        if (level == null) {
            return 6;
        }
        switch (level.toInt()) {
            case Level.ERROR_INT:
                return 3;
            case Level.WARN_INT:
                return 4;
            case Level.INFO_INT:
                return 6;
            default:
                return 7; // DEBUG and TRACE
        }
    }

    private SSLContext buildSslContext() throws Exception {
        String certPem = resolvePem(clientCertFile, clientCertPem, "syslog client certificate");
        String keyPem = resolvePem(clientKeyFile, clientKeyPem, "syslog client key");
        String trustPem = resolvePem(caFile, caPem, "syslog CA");

        boolean hasCert = notBlank(certPem);
        boolean hasKey = notBlank(keyPem);
        if (hasCert != hasKey) {
            addError("Syslog TLS client auth requires both a client certificate and a private "
                    + "key; only one was provided. Syslog appender disabled.");
            return null;
        }

        KeyManager[] keyManagers = null;
        if (hasCert) {
            List<X509Certificate> chain = PemUtils.parseCertificates(certPem);
            PrivateKey key = PemUtils.parsePrivateKey(keyPem);
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(null, null);
            keyStore.setKeyEntry("client", key, new char[0],
                    chain.toArray(new X509Certificate[0]));
            KeyManagerFactory kmf =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, new char[0]);
            keyManagers = kmf.getKeyManagers();
        }

        TrustManager[] trustManagers = null; // null = platform default trust
        if (notBlank(trustPem)) {
            List<X509Certificate> caCerts = PemUtils.parseCertificates(trustPem);
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            for (int i = 0; i < caCerts.size(); i++) {
                trustStore.setCertificateEntry("ca-" + i, caCerts.get(i));
            }
            TrustManagerFactory tmf =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);
            trustManagers = tmf.getTrustManagers();
        }

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers, trustManagers, new SecureRandom());
        return context;
    }

    /** File variant wins over inline PEM when both are set. */
    private String resolvePem(String file, String inline, String what) throws IOException {
        if (notBlank(file)) {
            try {
                return Files.readString(Path.of(file.trim()), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IOException("Cannot read " + what + " file '" + file.trim() + "': "
                        + e.getMessage(), e);
            }
        }
        return inline == null ? "" : inline;
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
