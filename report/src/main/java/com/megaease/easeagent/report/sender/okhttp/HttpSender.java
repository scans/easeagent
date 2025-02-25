/*
 * Copyright (c) 2021, MegaEase
 * All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.megaease.easeagent.report.sender.okhttp;

import com.google.auto.service.AutoService;
import com.megaease.easeagent.plugin.api.config.Config;
import com.megaease.easeagent.plugin.async.AgentThreadFactory;
import com.megaease.easeagent.plugin.report.Call;
import com.megaease.easeagent.plugin.report.EncodedData;
import com.megaease.easeagent.plugin.report.Sender;
import com.megaease.easeagent.plugin.utils.NoNull;
import com.megaease.easeagent.plugin.utils.common.StringUtils;
import com.megaease.easeagent.report.plugin.NoOpCall;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okhttp3.tls.Certificates;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import okio.Buffer;
import okio.BufferedSink;
import okio.GzipSink;
import okio.Okio;

import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.megaease.easeagent.config.report.ReportConfigConst.*;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

@Slf4j
@AutoService(Sender.class)
public class HttpSender implements Sender {

    public static final String SENDER_NAME = ZIPKIN_SENDER_NAME;

    private static final String AUTH_HEADER = "Authorization";


    private static final String ENABLED_KEY = "enabled";
    private static final String URL_KEY = "url";
    private static final String USERNAME_KEY = "username";
    private static final String PASSWORD_KEY = "password";
    private static final String GZIP_KEY = "compress";
    private static final String MAX_REQUESTS_KEY = "maxRequests";

    private static final String SERVER_USER_NAME_KEY = join(OUTPUT_SERVER_V2, USERNAME_KEY);
    private static final String SERVER_PASSWORD_KEY = join(OUTPUT_SERVER_V2, PASSWORD_KEY);
    private static final String SERVER_GZIP_KEY = join(OUTPUT_SERVER_V2, GZIP_KEY);

    private static final String TLS_ENABLE = join(OUTPUT_SERVER_V2, "tls.enable");

    // private key should be pkcs8 format
    private static final String TLS_KEY = join(OUTPUT_SERVER_V2, "tls.key");
    private static final String TLS_CERT = join(OUTPUT_SERVER_V2, "tls.cert");
    private static final String TLS_CA_CERT = join(OUTPUT_SERVER_V2, "tls.ca_cert");

    private String senderEnabledKey;
    private String urlKey;
    private String usernameKey;
    private String passwordKey;
    private String gzipKey;
    private String maxRequestsKey;

    private static final int MIN_TIMEOUT = 30_000;

    private Config config;

    private String url;
    private HttpUrl httpUrl;
    private String username;
    private String password;

    private boolean enabled;
    private boolean gzip;
    private boolean isAuth;

    private int timeout;
    private int maxRequests;

    private String credential;
    private OkHttpClient client;

    private Boolean tlsEnable;
    private String tlsKey;
    private String tlsCert;
    private String tlsCaCert;

    private String prefix;

    // URL-USER-PASSWORD as unique key shared a client
    static ConcurrentHashMap<String, OkHttpClient> clientMap = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return SENDER_NAME;
    }

    @Override
    public void init(Config config, String prefix) {
        this.prefix = prefix;
        extractConfig(config);
        this.config = config;
        initClient();
    }

    private void updatePrefix(String prefix) {
        senderEnabledKey = join(prefix, ENABLED_KEY);
        urlKey = join(prefix, URL_KEY);
        usernameKey = join(prefix, USERNAME_KEY);
        passwordKey = join(prefix, PASSWORD_KEY);
        gzipKey = join(prefix, GZIP_KEY);
        maxRequestsKey = join(prefix, MAX_REQUESTS_KEY);
    }

    private void extractConfig(Config config) {
        updatePrefix(this.prefix);
        this.url = getUrl(config);
        this.username = StringUtils.noEmptyOf(config.getString(usernameKey), config.getString(SERVER_USER_NAME_KEY));
        this.password = StringUtils.noEmptyOf(config.getString(passwordKey), config.getString(SERVER_PASSWORD_KEY));

        this.tlsEnable = config.getBoolean(TLS_ENABLE);
        this.tlsKey = config.getString(TLS_KEY);
        this.tlsCert = config.getString(TLS_CERT);
        this.tlsCaCert = config.getString(TLS_CA_CERT);

        this.gzip = NoNull.of(config.getBooleanNullForUnset(gzipKey),
            NoNull.of(config.getBooleanNullForUnset(SERVER_GZIP_KEY), true));

        this.timeout = NoNull.of(config.getInt(OUTPUT_SERVERS_TIMEOUT), MIN_TIMEOUT);
        if (this.timeout < MIN_TIMEOUT) {
            this.timeout = MIN_TIMEOUT;
        }
        this.enabled = NoNull.of(config.getBooleanNullForUnset(senderEnabledKey), true);
        this.maxRequests = NoNull.of(config.getInt(maxRequestsKey), 65);

        if (StringUtils.isEmpty(url) || Boolean.FALSE.equals(config.getBoolean(OUTPUT_SERVERS_ENABLE))) {
            this.enabled = false;
        } else {
            this.httpUrl = HttpUrl.parse(this.url);
            if (this.httpUrl == null) {
                log.error("Invalid Url:{}", this.url);
                this.enabled = false;
            }
        }

        this.isAuth = !StringUtils.isEmpty(username) && !StringUtils.isEmpty(password);
        if (isAuth) {
            this.credential = Credentials.basic(username, password);
        }
    }

    private String getUrl(Config config) {
        // url
        String outputServer = config.getString(BOOTSTRAP_SERVERS);
        String cUrl = NoNull.of(config.getString(urlKey), "");
        if (!StringUtils.isEmpty(outputServer) && !cUrl.startsWith("http")) {
            cUrl = outputServer + cUrl;
        }
        return cUrl;
    }

    @Override
    public Call<Void> send(EncodedData encodedData) {
        if (!enabled) {
            return NoOpCall.getInstance(Void.class);
        }
        Request request;

        try {
            if (encodedData instanceof RequestBody) {
                request = newRequest((RequestBody) encodedData);
            } else {
                request = newRequest(new ByteRequestBody(encodedData.getData()));
            }
        } catch (IOException e) {
            // log rate-limit
            if (log.isDebugEnabled()) {
                log.debug("tracing send fail!");
            }
            return NoOpCall.getInstance(Void.class);
        }

        return new HttpCall(client.newCall(request));
    }

    @Override
    public boolean isAvailable() {
        return this.enabled;
    }

    @Override
    public void updateConfigs(Map<String, String> changes) {
        this.config.updateConfigsNotNotify(changes);

        String newUserName = StringUtils.noEmptyOf(config.getString(usernameKey), config.getString(SERVER_USER_NAME_KEY));
        String newPwd = StringUtils.noEmptyOf(config.getString(passwordKey), config.getString(SERVER_PASSWORD_KEY));
        // check new client
        boolean renewClient = !getUrl(this.config).equals(this.url)
            || !org.apache.commons.lang3.StringUtils.equals(newUserName, this.username)
            || !org.apache.commons.lang3.StringUtils.equals(newPwd, this.password)
            || !org.apache.commons.lang3.StringUtils.equals(this.config.getString(TLS_CA_CERT), this.tlsCaCert)
            || !org.apache.commons.lang3.StringUtils.equals(this.config.getString(TLS_CERT), this.tlsCert)
            || !org.apache.commons.lang3.StringUtils.equals(this.config.getString(TLS_KEY), this.tlsKey);

        if (renewClient) {
            clearClient();
            extractConfig(this.config);
            newClient();
        }
    }

    @Override
    public void close() throws IOException {
        clearClient();
    }

    /**
     * Waits up to a second for in-flight requests to finish before cancelling them
     */
    private void clearClient() {
        OkHttpClient dClient = clientMap.remove(getClientKey());
        if (dClient == null) {
            return;
        }
        Dispatcher dispatcher = dClient.dispatcher();
        dispatcher.executorService().shutdown();
        try {
            if (!dispatcher.executorService().awaitTermination(1, TimeUnit.SECONDS)) {
                dispatcher.cancelAll();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // different url for different business, so create separate clients with different dispatcher
    private String getClientKey() {
        return this.url + ":" + this.username + ":" + this.password;
    }

    private void newClient() {
        String clientKey = getClientKey();
        OkHttpClient newClient = clientMap.get(clientKey);
        if (newClient != null) {
            client = newClient;
            return;
        }
        OkHttpClient.Builder builder = new OkHttpClient.Builder();

        // timeout
        builder.connectTimeout(timeout, MILLISECONDS);
        builder.readTimeout(timeout, MILLISECONDS);
        builder.writeTimeout(timeout, MILLISECONDS);

        // auth
        if (this.isAuth) {
            appendBasicAuth(builder, this.credential);
        }
        // tls
        if (Boolean.TRUE.equals(this.tlsEnable)) {
            appendTLS(builder, this.tlsCaCert, this.tlsCert, this.tlsKey);
        }
        synchronized (HttpSender.class) {
            if (clientMap.get(clientKey) != null) {
                client = clientMap.get(clientKey);
            } else {
                builder.dispatcher(newDispatcher(maxRequests));
                newClient = builder.build();
                clientMap.putIfAbsent(clientKey, newClient);
                client = newClient;
            }
        }
    }

    public static void appendBasicAuth(OkHttpClient.Builder builder, String basicUser, String basicPassword) {
        builder.addInterceptor(chain -> {
            Request request = chain.request();
            Request authRequest = request.newBuilder()
                .header(AUTH_HEADER, Credentials.basic(basicUser, basicPassword)).build();
            return chain.proceed(authRequest);
        });
    }

    public static void appendBasicAuth(OkHttpClient.Builder builder, String basicCredential) {
        builder.addInterceptor(chain -> {
            Request request = chain.request();
            Request authRequest = request.newBuilder()
                .header(AUTH_HEADER, basicCredential).build();
            return chain.proceed(authRequest);
        });
    }

    public static void appendTLS(OkHttpClient.Builder builder, String tlsCaCert, String tlsCert, String tlsKey) {
        // Create the root for client and server to trust. We could also use different roots for each!
        X509Certificate clientX509Certificate = Certificates.decodeCertificatePem(tlsCert);
        X509Certificate rootX509Certificate = Certificates.decodeCertificatePem(tlsCaCert);
        // Create a client certificate and a client that uses it.
        HeldCertificate clientCertificateKey = HeldCertificate.decode(tlsCert + tlsKey);
        HandshakeCertificates clientCertificates = new HandshakeCertificates.Builder()
            .addTrustedCertificate(rootX509Certificate)
            .heldCertificate(clientCertificateKey, clientX509Certificate)
            .build();
        builder.sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager());
    }

    private void initClient() {
        if (client != null) {
            return;
        }
        newClient();
    }

    // borrow form zipkin-reporter
    private Request newRequest(RequestBody body) throws IOException {
        Request.Builder request = new Request.Builder().url(httpUrl);
        // Amplification can occur when the Zipkin endpoint is accessed through a proxy, and the proxy is instrumented.
        // This prevents that in proxies, such as Envoy, that understand B3 single format,
        request.addHeader("b3", "0");
        if (this.isAuth) {
            request.header(AUTH_HEADER, credential);
        }
        if (this.gzip) {
            request.addHeader("Content-Encoding", "gzip");
            Buffer gzipped = new Buffer();
            BufferedSink gzipSink = Okio.buffer(new GzipSink(gzipped));
            body.writeTo(gzipSink);
            gzipSink.close();
            body = new BufferRequestBody(body.contentType(), gzipped);
        }
        request.post(body);
        return request.build();
    }

    static Dispatcher newDispatcher(int maxRequests) {
        // bound the executor so that we get consistent performance
        ThreadPoolExecutor dispatchExecutor =
            new ThreadPoolExecutor(0, maxRequests, 60, TimeUnit.SECONDS,
                // Using a synchronous queue means messages will send immediately until we hit max
                // in-flight requests. Once max requests are hit, send will block the caller, which is
                // the AsyncReporter flush thread. This is ok, as the AsyncReporter has a buffer of
                // unsent spans for this purpose.
                new SynchronousQueue<>(),
                OkHttpSenderThreadFactory.INSTANCE);

        Dispatcher dispatcher = new Dispatcher(dispatchExecutor);
        dispatcher.setMaxRequests(maxRequests);
        dispatcher.setMaxRequestsPerHost(maxRequests);
        return dispatcher;
    }

    static class OkHttpSenderThreadFactory extends AgentThreadFactory {
        public static final OkHttpSenderThreadFactory INSTANCE = new OkHttpSenderThreadFactory();

        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, "AgentHttpSenderDispatcher-" + createCount.getAndIncrement());
        }
    }

    // from zipkin-reporter-java
    static final class BufferRequestBody extends RequestBody {
        final MediaType contentType;
        final Buffer body;

        BufferRequestBody(MediaType contentType, Buffer body) {
            this.contentType = contentType;
            this.body = body;
        }

        @Override
        public long contentLength() {
            return body.size();
        }

        @Override
        public MediaType contentType() {
            return contentType;
        }

        @Override
        public void writeTo(BufferedSink sink) throws IOException {
            sink.write(body, body.size());
        }
    }
}
