package rest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public class RestService {
    private static final Logger log = LoggerFactory.getLogger(RestService.class);
    private static final Logger requestLog = LoggerFactory.getLogger(RestService.class.getName() + ".request");
    private static final Logger responseLog = LoggerFactory.getLogger(RestService.class.getName() + ".response");

    private static final ConnectionPool CONNECTION_POOL = new ConnectionPool(100,
            30, TimeUnit.SECONDS);
    private static final String TOKEN_SERVICE_PATH = "/rest/oauthTokenService/v1/token";

    private static class Token {
        private final String token;
        private final Instant expirationTime;

        public Token(String token, Instant expirationTime) {
            this.token = token;
            this.expirationTime = expirationTime;
        }
    }
    private static final ConcurrentHashMap<Credentials, Token> tokenCache = new ConcurrentHashMap<>();

    @Nonnull
    protected final String url;
    @Nonnull
    protected final Credentials credentials;
    protected final int connectTimeout;
    protected final int responseTimeout;
    protected final int retryCount;
    protected final int maxConcurrentRequests;
    protected OkHttpClient httpClient;
    private JsonParser jsonParser;
    /** para detectar cancelaciones durante la execución de los métodos */
    //private volatile long lastCancelTimestamp = System.nanoTime();

    protected static final MediaType MEDIA_TYPE_JSON
            = MediaType.parse("application/json; charset=utf-8");

    public RestService(@Nonnull String url, @Nonnull Credentials credentials) {
        this(url, credentials, 5000, 20000, 0, 10);
    }

    public RestService(@Nonnull String url, @Nonnull Credentials credentials,
                       int connectTimeout, int responseTimeout, int retryCount,
                       int maxConcurrentRequests) {
        this(url, credentials, connectTimeout, responseTimeout, retryCount, maxConcurrentRequests, null);
    }

    public RestService(@Nonnull String url, @Nonnull Credentials credentials,
                       int connectTimeout, int responseTimeout, int retryCount,
                       int maxConcurrentRequests,
                       @Nullable ProxyConfig proxyConfig) {
        this.credentials = credentials;
        this.maxConcurrentRequests = maxConcurrentRequests;
        if (url.endsWith("/")) {
            this.url = url.substring(0, url.lastIndexOf("/"));
        } else {
            this.url = url;
        }
        this.connectTimeout = connectTimeout;
        this.responseTimeout = responseTimeout;
        this.retryCount = retryCount;
        // proxy settings
        Proxy proxy = null;
        Authenticator proxyAuthenticator = null;
        if (proxyConfig != null) {
            //log.debug("Using proxy {}:{}", proxyConfig.host, proxyConfig.port);
            proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyConfig.host, proxyConfig.port));
            if (proxyConfig.user != null && !proxyConfig.user.isEmpty() && proxyConfig.password != null && !proxyConfig.password.isEmpty()) {
                //log.debug("Using proxyAuthenticator {}", proxyConfig.user);
                proxyAuthenticator = (Route route, Response response) -> {
                    String credential = okhttp3.Credentials.basic(proxyConfig.user, proxyConfig.password);
                    return response.request().newBuilder()
                            .header("Proxy-Authorization", credential)
                            .build();
                };
            }
        }
        OkHttpClient.Builder httpBuilder = new OkHttpClient().newBuilder();
        if (proxy != null)
            httpBuilder.proxy(proxy);
        if (proxyAuthenticator != null)
            httpBuilder.proxyAuthenticator(proxyAuthenticator);
        httpClient = httpBuilder
                .connectTimeout(this.connectTimeout, TimeUnit.MILLISECONDS)
                .readTimeout(responseTimeout, TimeUnit.MILLISECONDS)
                .writeTimeout(responseTimeout, TimeUnit.MILLISECONDS)
                .connectionPool(CONNECTION_POOL)
                .build();
        httpClient.dispatcher().setMaxRequests(maxConcurrentRequests);
        httpClient.dispatcher().setMaxRequestsPerHost(maxConcurrentRequests);
        jsonParser = new JsonParser();
    }

    public void cancel() {
        //this.lastCancelTimestamp = System.nanoTime();
        httpClient.dispatcher().cancelAll();
        log.trace("Queued REST calls cancelled");
    }

    /**
     * Execute requests in parallel and do not throw any exception. IOExceptions are returned within TOAResult.
     * @param requests requests
     * @param resultTransformer transformer
     * @param <R> data type
     * @return [requestId -> TOAResult<R>]
     * @throws IOException if any IO error occurs
     */
    public <R> Map<String, TOAResult<R>> executeParallelIgnoreIOException(Map<String, okhttp3.Request> requests,
                                                                          @Nullable Function<JsonElement, R> resultTransformer) throws IOException
    {
        long startTimestamp = System.nanoTime();
        CountDownLatch countDownLatch = new CountDownLatch(requests.size());
        Map<String, TOAResult<R>> results = new LinkedHashMap<>();
        // initialize with requestId -> null to maintain original order
        requests.keySet().forEach(k -> results.put(k, null));

        for (Map.Entry<String, Request> entry : requests.entrySet()) {
            final String reqId = entry.getKey();
            final Request request = entry.getValue();
            final Request req = request.newBuilder()
                    .header("Authorization", authorizationHeader())
                    .build();

            if (requestLog.isTraceEnabled())
                requestLog.trace(request.toString());

            httpClient.newCall(req).enqueue(new Callback() {
                @Override
                public void onFailure(@Nonnull Call call, @Nonnull IOException e) {
                    responseLog.trace(e.getMessage());
                    synchronized (results) {
                        results.put(reqId, TOAResult.ofError(e));
                    }
                    countDownLatch.countDown();
                }

                @Override
                public void onResponse(@Nonnull Call call, @Nonnull Response response) throws IOException {
                    try {
                        final String bodyString = responseBody(response);
                        if (response.isSuccessful()) {
                            R res;
                            if (resultTransformer != null) {
                                res = resultTransformer.apply(jsonParser.parse(bodyString));
                            } else {
                                res = null;
                            }

                            synchronized (results) {
                                results.put(reqId, TOAResult.of(res));
                            }
                            countDownLatch.countDown();
                        } else {
                            final int status = response.code();
                            final String message = parseToaError(bodyString).orElse(response.message());

                            synchronized (results) {
                                results.put(reqId, TOAResult.ofError(new TOAException(status, message
                                        + ", request=" + request.toString())));
                            }
                            countDownLatch.countDown();
                        }
                    } catch (IOException ex) {
                        onFailure(call, new IOException(
                                "Error processing TOA response, request="
                                        + request.toString() + ", response=" + response.toString(), ex));
                    }
                }
            });
        }

        try {
            countDownLatch.await();
        } catch (InterruptedException e) {
            //Thread.currentThread().interrupt();
            throw new IOException(e);
        }

        return results;
    }


    /**
     * Execute requests in parallel. Throws exception only if any IO error occurs (but not TOA)
     * @param requests requests
     * @param resultTransformer transformer
     * @param <R> data type
     * @return [requestId -> TOAResult<R>]
     * @throws IOException if any IO error occurs
     */
    public <R> Map<String, TOAResult<R>> executeParallel(Map<String, okhttp3.Request> requests,
                                                        @Nullable Function<JsonElement, R> resultTransformer) throws IOException
    {
        CountDownLatch countDownLatch = new CountDownLatch(requests.size());
        Map<String, TOAResult<R>> results = new LinkedHashMap<>();
        // initialize with requestId -> null to maintain original order
        requests.keySet().forEach(k -> results.put(k, null));
        AtomicReference<IOException> exception = new AtomicReference<>();

        for (Map.Entry<String, Request> entry : requests.entrySet()) {
            final String reqId = entry.getKey();
            final Request request = entry.getValue();
            final Request req = request.newBuilder()
                    .header("Authorization", authorizationHeader())
                    .build();

            if (requestLog.isTraceEnabled())
                requestLog.trace(request.toString());

            httpClient.newCall(req).enqueue(new Callback() {
                @Override
                public void onFailure(@Nonnull Call call, @Nonnull IOException e) {
                    responseLog.trace(e.getMessage());
                    boolean isFirstFault = exception.compareAndSet(null, e);
                    countDownLatch.countDown();
                    if (isFirstFault) cancel();
                }

                @Override
                public void onResponse(@Nonnull Call call, @Nonnull Response response) throws IOException {
                    try {
                        final String bodyString = responseBody(response);
                        if (response.isSuccessful()) {
                            R res;
                            if (resultTransformer != null) {
                                res = resultTransformer.apply(jsonParser.parse(bodyString));
                            } else {
                                res = null;
                            }

                            synchronized (results) {
                                results.put(reqId, TOAResult.of(res));
                            }
                            countDownLatch.countDown();
                        } else {
                            final int status = response.code();
                            final String message = parseToaError(bodyString).orElse(response.message());

                            synchronized (results) {
                                results.put(reqId, TOAResult.ofError(new TOAException(status, message
                                        + ", request=" + request.toString())));
                            }
                            countDownLatch.countDown();
                        }
                    } catch (IOException ex) {
                        onFailure(call, new IOException(
                                "Error processing TOA response, request="
                                        + request.toString() + ", response=" + response.toString(), ex));
                    }
                }
            });
        }

        try {
            countDownLatch.await();
        } catch (InterruptedException e) {
            //Thread.currentThread().interrupt();
            throw new IOException(e);
        }

        if (exception.get() != null)
            // rethrow original exception with new stackTrace. Only IOException may be produced in this methods.
            throw new IOException(exception.get());

        return results;
    }


    /**
     * Execute requests in parallel. Throws exception if any error occurs (connection or TOA)
     * @param requests requests
     * @param resultTransformer result transformer
     * @param <R> param
     * @return [requestId -> R]
     * @throws IOException if any connection error occurs
     * @throws TOAException if TOA reports error as a result of any request
     */
    public <R> Map<String, R> executeAll(Map<String, okhttp3.Request> requests,
                                                     @Nullable Function<JsonElement, R> resultTransformer) throws IOException {
        CountDownLatch countDownLatch = new CountDownLatch(requests.size());
        Map<String, R> results = new LinkedHashMap<>();
        // initialize with requestId -> null to maintain original order
        requests.keySet().forEach(k -> results.put(k, null));
        AtomicReference<IOException> exception = new AtomicReference<>();

        for (Map.Entry<String, Request> entry : requests.entrySet()) {
            final String reqId = entry.getKey();
            final Request request = entry.getValue();
            final Request req = request.newBuilder()
                    .header("Authorization", authorizationHeader())
                    .build();

            if (requestLog.isTraceEnabled())
                requestLog.trace(request.toString());

            httpClient.newCall(req).enqueue(new Callback() {
                @Override
                public void onFailure(@Nonnull Call call, @Nonnull IOException e) {
                    responseLog.trace(e.getMessage());
                    boolean isFirstFault = exception.compareAndSet(null, e);
                    countDownLatch.countDown();
                    if (isFirstFault) cancel();
                }

                @Override
                public void onResponse(@Nonnull Call call, @Nonnull Response response) throws IOException {
                    final String bodyString = responseBody(response);
                    if (response.isSuccessful()) {
                        R res;
                        if (resultTransformer != null) {
                            res = resultTransformer.apply(jsonParser.parse(bodyString));
                        } else {
                            res = null;
                        }

                        synchronized (results) {
                            results.put(reqId, res);
                        }
                        countDownLatch.countDown();
                    } else {
                        final int status = response.code();
                        final String message = parseToaError(bodyString).orElse(response.message());
                        onFailure(call, new TOAException(status, message
                                        + ", request=" + request.toString()));
                    }
                }
            });
        }

        try {
            countDownLatch.await();
        } catch (InterruptedException e) {
            //Thread.currentThread().interrupt();
            throw new IOException(e);
        }

        if (exception.get() != null)
            if (exception.get() instanceof TOAException)
                throw new TOAException((TOAException) exception.get());
            else
                throw new IOException(exception.get());

        return results;
    }


    public JsonElement execute(Request request) throws IOException {
        if (requestLog.isTraceEnabled())
            requestLog.trace(request.toString());

        Request req = request.newBuilder()
                .header("Authorization", authorizationHeader())
                .build();

        final Response response = httpClient.newCall(req).execute();
        final String bodyString = responseBody(response);

        if (!response.isSuccessful()) {
            final int status = response.code();
            final String message = parseToaError(bodyString).orElse(response.message());
            throw new TOAException(status, message);
        }

        return jsonParser.parse(bodyString);
    }


    protected Request.Builder request() {
        return new Request.Builder().url(this.url);
    }


    /**
     * According to this:
     * https://square.github.io/okhttp/3.x/okhttp/okhttp3/ResponseBody.html
     * a response body must always be closed explicitly on EVERY request
     */
    private String responseBody(Response response) throws IOException {
        final ResponseBody body = response.body();
        if (body == null) throw new IOException("Empty response body: " + response);
        try {
            final String bodyString = body.string();
            if (log.isTraceEnabled())
                log.trace(bodyString);
            return bodyString;
        } finally {
            body.close();
        }
    }

    /**
        Try to parse TOA response:
        {
            "type": "http://www.w3.org/Protocols/rfc2616/rfc2616-sec10.html#sec10.4.10",
            "title" : "Conflict",
            "status": "409",
            "detail": "Activity with this status cannot execute this action"
        }
      */
    private Optional<String> parseToaError(String responseBody) {
        String message = null;
        try {
            JsonObject res = jsonParser.parse(responseBody).getAsJsonObject();
            if (res.has("type")
                    && res.has("title")
                //&& res.has("status")
            ) {
                //status = res.get("status").getAsInt();
                String title = res.get("title").getAsString();
                if (res.has("detail")) {
                    message = res.get("detail").getAsString();
                    if (!title.equals(message))
                        message = title + " - " + message;
                } else
                    message = title;
            }
        } catch (Exception e) { /*ignore*/}
        return Optional.ofNullable(message);
    }


    protected static List<JsonObject> items(JsonElement toaResponse) {
        JsonObject jsonResponse = toaResponse.getAsJsonObject();
        JsonArray jsonItemRecords = jsonResponse.getAsJsonArray("items");
        List<JsonObject> jsonItems;
        if (jsonItemRecords != null) {
            jsonItems = new ArrayList<>(jsonItemRecords.size());
            for (JsonElement propertyRecord : jsonItemRecords) {
                if (!propertyRecord.isJsonNull()) {
                    JsonObject object = propertyRecord.getAsJsonObject();
                    object.remove("links");
                    jsonItems.add(object);
                }
            }
        } else
            jsonItems = Collections.emptyList();
        return jsonItems;
    }

    protected static <R> List<R> items(JsonElement toaResponse, Function<JsonObject, R> converter) {
        JsonObject jsonResponse = toaResponse.getAsJsonObject();
        JsonArray jsonItemRecords = jsonResponse.getAsJsonArray("items");
        List<R> jsonItems;
        if (jsonItemRecords != null) {
            jsonItems = new ArrayList<>(jsonItemRecords.size());
            for (JsonElement propertyRecord : jsonItemRecords) {
                if (!propertyRecord.isJsonNull()) {
                    JsonObject object = propertyRecord.getAsJsonObject();
                    //object.remove("links");
                    jsonItems.add(converter.apply(object));
                }
            }
        } else
            jsonItems = Collections.emptyList();
        return jsonItems;
    }

    protected static Function<JsonElement, Void> voidTransformer() {
        return null;
    }

    /**
     * Constructs an authorization header. Request token if necessary.
     */
    public String authorizationHeader() throws IOException {
        // OAuth2 authentication
        Token token = tokenCache.get(credentials);
        final Instant now = Instant.now();
        if (token == null || token.expirationTime.isBefore(now)) {
            //synchronized(RestService.class) {
                // double-check after synchronization block
                token = tokenCache.get(credentials);
                if (token == null || token.expirationTime.isBefore(now)) {
                    /*
                    {
                        "token": "eyJ0eXAiOiJ...SKIP...qtCqNDk6qy_utky5M",
                        "token_type": "bearer",
                        "expires_in": 3600
                    }
                     */
                    final JsonObject accessToken = getAccessToken();
                    final String sToken = accessToken.get("token").getAsString();
                    final int expiresIn = accessToken.get("expires_in").getAsInt();
                    // reserve margin of 10 minutes before expiration
                    token = new Token(sToken, now.plusSeconds(expiresIn - 600));
                    // cleanup expired tokens
                    tokenCache.entrySet().removeIf(it -> it.getValue().expirationTime.isBefore(now));
                    tokenCache.put(credentials, token);
                    log.debug("New access token requested");
                }
            //}
        }
        return "Bearer " + token.token;
    }

    /**
     * https://docs.oracle.com/en/cloud/saas/field-service/18a/cxfsc/op-rest-oauthtokenservice-v1-token-post.html
     */
    private JsonObject getAccessToken() throws IOException
    {
        final HttpUrl url = HttpUrl.parse(this.url).newBuilder()
                .encodedPath(TOKEN_SERVICE_PATH)
                .query(null)
                .build();
        final Request request = new Request.Builder()
                .url(url)
                .header("Authorization", okhttp3.Credentials.basic(
                        credentials.login + "@" + credentials.company, credentials.password))
                .post(new FormBody.Builder().add("grant_type", "client_credentials").build())
                .build();

        if (requestLog.isTraceEnabled())
            requestLog.trace(request.toString());

        final Response response = httpClient.newCall(request).execute();
        final String bodyString = responseBody(response);

        if (!response.isSuccessful()) {
            final int status = response.code();
            final String message = parseToaError(bodyString).orElse(response.message());
            throw new TOAException(status, message);
        }

        return jsonParser.parse(bodyString).getAsJsonObject();
    }
}
