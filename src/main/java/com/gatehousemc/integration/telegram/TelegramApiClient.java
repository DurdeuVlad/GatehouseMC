package com.gatehousemc.integration.telegram;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

final class TelegramApiClient implements TelegramTransport {
    private final String baseUrl;
    private final HttpClient client = HttpClient.newHttpClient();

    TelegramApiClient(String token) {
        this.baseUrl = "https://api.telegram.org/bot" + token + "/";
    }

    public CompletableFuture<JsonObject> post(String method, String payload) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + method))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if (response.statusCode() / 100 != 2) throw new IllegalStateException("Telegram HTTP " + response.statusCode());
                    JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
                    if (!body.get("ok").getAsBoolean()) throw new IllegalStateException("Telegram API rejected request");
                    return body;
                });
    }
}
