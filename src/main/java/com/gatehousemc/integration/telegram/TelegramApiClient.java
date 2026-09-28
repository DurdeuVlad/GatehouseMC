package com.gatehousemc.integration.telegram;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

final class TelegramApiClient implements TelegramTransport {
    private final String baseUrl;
    private final HttpClient client;
    private final Set<CompletableFuture<?>> requests = ConcurrentHashMap.newKeySet();
    private final Object requestLock = new Object();
    private boolean closed;

    TelegramApiClient(String token) {
        this(token, HttpClient.newHttpClient());
    }

    TelegramApiClient(String token, HttpClient client) {
        this.baseUrl = "https://api.telegram.org/bot" + token + "/";
        this.client = client;
    }

    public CompletableFuture<JsonObject> post(String method, String payload) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + method))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        CompletableFuture<HttpResponse<String>> rawResponse;
        synchronized (requestLock) {
            if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Telegram transport is closed"));
            rawResponse = client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            requests.add(rawResponse);
        }
        rawResponse.whenComplete((ignored, error) -> requests.remove(rawResponse));
        return rawResponse
                .thenApply(response -> {
                    if (response.statusCode() / 100 != 2) throw new IllegalStateException("Telegram HTTP " + response.statusCode());
                    JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
                    if (!body.get("ok").getAsBoolean()) throw new IllegalStateException("Telegram API rejected request");
                    return body;
                });
    }

    @Override
    public void close() {
        synchronized (requestLock) {
            closed = true;
            requests.forEach(request -> request.cancel(true));
            requests.clear();
        }
    }
}
