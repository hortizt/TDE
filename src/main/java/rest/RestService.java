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


        OkHttpClient.Builder httpBuilder = new OkHttpClient().newBuilder();

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
