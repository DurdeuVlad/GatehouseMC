package com.gatehousemc.integration.telegram;

import com.google.gson.JsonObject;

import java.util.concurrent.CompletableFuture;

/**
 * Transport port for Telegram Bot API calls. Allows fake transports in tests
 * without hitting the real Telegram API.
 */
public interface TelegramTransport {
    CompletableFuture<JsonObject> post(String method, String payload);
}
