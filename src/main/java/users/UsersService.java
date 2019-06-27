package users;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import rest.Credentials;
import rest.RestService;
import rest.ProxyConfig;
import okhttp3.HttpUrl;
import okhttp3.Request;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class UsersService extends RestService implements UsersPort {

    public UsersService(@Nonnull String url, @Nonnull Credentials credentials) {
        super(url, credentials);
    }

    public UsersService(@Nonnull String url, @Nonnull Credentials credentials, int connectTimeout, int responseTimeout, int retryCount, int maxConcurrentRequests) {
        super(url, credentials, connectTimeout, responseTimeout, retryCount, maxConcurrentRequests);
    }

    public UsersService(@Nonnull String url, @Nonnull Credentials credentials, int connectTimeout, int responseTimeout, int retryCount, int maxConcurrentRequests, @Nullable ProxyConfig proxyConfig) {
        super(url, credentials, connectTimeout, responseTimeout, retryCount, maxConcurrentRequests, proxyConfig);
    }

    @Nonnull
    @Override
    public List<JsonObject> users() throws IOException {
        Request request = request().get().build();

        return executeIterative(request);
    }

    @Override
    public JsonObject user(@Nonnull String login) throws IOException {
        // GET https://api.toadirect.com/rest/ofscCore/v1/users/{login}
        Request request = request()
                .url(HttpUrl.parse(this.url).newBuilder()
                        .addPathSegment(login)
                        .build()
                )
                .get().build();
        return execute(request).getAsJsonObject();
    }

    protected List<JsonObject> executeIterative(Request request) throws IOException {
        List<JsonObject> items = new ArrayList<>();

        // first call: 0-99
        Request req = request.newBuilder()
                .url(request.url().newBuilder()
                        .addQueryParameter("limit", "100")
                        .addQueryParameter("offset", "0")
                        .build()
                ).build();
        JsonElement res = execute(req);
        JsonObject response = res.getAsJsonObject();
        int totalResults = response.get("totalResults").getAsInt();

        JsonArray itemRecords = response.getAsJsonArray("items");

        if (itemRecords != null) {
            for (JsonElement propertyRecord : itemRecords) {
                if (!propertyRecord.isJsonNull())
                    items.add(propertyRecord.getAsJsonObject());
            }
        }

        if (totalResults <= 100)
            return items;

        // next calls in parallel
        Map<String, Request> requests = new LinkedHashMap<>();

        for (int ofs = 100;; ofs += 100) {
            req = request.newBuilder()
                    .url(request.url().newBuilder()
                            .addQueryParameter("limit", "100")
                            .addQueryParameter("offset", String.valueOf(ofs))
                            .build()
                    ).build();
            requests.put(String.valueOf(ofs), req);

            if (ofs + 100 >= totalResults)
                break;
        }

        Map<String, List<JsonObject>> itemsSlices = executeAll(requests, UsersService::items);

        for (String ofs : requests.keySet()) {
            List<JsonObject> jsonObjects = itemsSlices.get(ofs);
            if (jsonObjects != null)
                items.addAll(jsonObjects);
        }

        return items;
    }

    /*private static List<JsonObject> items(JsonElement jsonElement) {
        JsonObject jsonResponse = jsonElement.getAsJsonObject();
        JsonArray jsonItemRecords = jsonResponse.getAsJsonArray("items");
        List<JsonObject> jsonItems = new ArrayList<>(100);
        if (jsonItemRecords != null) {
            for (JsonElement propertyRecord : jsonItemRecords) {
                if (!propertyRecord.isJsonNull())
                    jsonItems.add(propertyRecord.getAsJsonObject());
            }
        }
        return jsonItems;
    }*/
}
