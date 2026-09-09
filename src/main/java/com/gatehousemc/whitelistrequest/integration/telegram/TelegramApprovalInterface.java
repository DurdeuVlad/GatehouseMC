package com.gatehousemc.whitelistrequest.integration.telegram;

import com.gatehousemc.whitelistrequest.application.DecisionService;
import com.gatehousemc.whitelistrequest.config.ModConfig;
import com.gatehousemc.whitelistrequest.domain.*;
import com.gatehousemc.whitelistrequest.i18n.Messages;
import com.gatehousemc.whitelistrequest.integration.common.ApprovalMessageRenderer;
import com.gatehousemc.whitelistrequest.integration.common.CallbackActionParser;
import com.gatehousemc.whitelistrequest.port.ApprovalInterface;
import com.gatehousemc.whitelistrequest.port.ProviderHealth;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Telegram Bot API adapter using Java 21 HttpClient and a dedicated long-polling thread. */
public final class TelegramApprovalInterface implements ApprovalInterface {
    private final ModConfig.Telegram config;
    private final DecisionService decisions;
    private volatile ProviderHealth health = ProviderHealth.STOPPED;
    private volatile TelegramTransport api;
    private volatile ExecutorService poller;
    private volatile boolean running;
    private volatile long offset;

    public TelegramApprovalInterface(ModConfig.Telegram config, DecisionService decisions) {
        this.config = config;
        this.decisions = decisions;
    }

    /** Package-private constructor for fake-transport testing. */
    TelegramApprovalInterface(ModConfig.Telegram config, DecisionService decisions, TelegramTransport transport) {
        this.config = config;
        this.decisions = decisions;
        this.api = transport;
        this.health = ProviderHealth.HEALTHY;
    }

    @Override
    public String id() { return "telegram"; }

    @Override
    public ProviderHealth health() { return health; }

    @Override
    public void start() {
        if (!config.enabled() || config.token() == null || config.token().isBlank() || config.chatId().isBlank()) {
            health = ProviderHealth.UNAVAILABLE;
            return;
        }
        api = new TelegramApiClient(config.token());
        running = true;
        health = ProviderHealth.STARTING;
        poller = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "whitelistrequest-telegram");
            thread.setDaemon(true);
            return thread;
        });
        poller.submit(this::pollLoop);
    }

    @Override
    public void stop() {
        running = false;
        health = ProviderHealth.STOPPED;
        ExecutorService current = poller;
        poller = null;
        if (current != null) current.shutdownNow();
    }

    @Override
    public CompletionStage<PublicationRef> publish(RequestView request) {
        TelegramTransport current = api;
        if (current == null) return CompletableFuture.failedFuture(new IllegalStateException("Telegram is stopped"));
        String payload = "{\"chat_id\":\"" + json(config.chatId()) + "\",\"text\":\"" + json(render(request)) + "\",\"reply_markup\":" + keyboard(request.id(), false) + "}";
        return current.post("sendMessage", payload).thenApply(body -> {
            JsonObject message = body.getAsJsonObject("result");
            return new PublicationRef(id(), config.chatId(), message.get("message_id").getAsString());
        });
    }

    @Override
    public CompletionStage<Void> update(PublicationRef publication, RequestView request) {
        TelegramTransport current = api;
        if (current == null) return CompletableFuture.failedFuture(new IllegalStateException("Telegram is stopped"));
        String payload = "{\"chat_id\":\"" + json(publication.containerId()) + "\",\"message_id\":\"" + json(publication.messageId()) + "\",\"text\":\"" + json(render(request)) + "\",\"reply_markup\":" + keyboard(request.id(), request.status().isTerminal()) + "}";
        return current.post("editMessageText", payload).thenApply(ignored -> null);
    }

    private void pollLoop() {
        while (running) {
            try {
                String payload = "{\"timeout\":20,\"offset\":" + offset + ",\"allowed_updates\":[\"callback_query\"]}";
                JsonArray updates = api.post("getUpdates", payload).join().getAsJsonArray("result");
                health = ProviderHealth.HEALTHY;
                updates.forEach(update -> {
                    JsonObject value = update.getAsJsonObject();
                    offset = Math.max(offset, value.get("update_id").getAsLong() + 1);
                    if (value.has("callback_query")) handleCallback(value.getAsJsonObject("callback_query"));
                });
            } catch (Exception error) {
                health = ProviderHealth.DEGRADED;
                try { TimeUnit.SECONDS.sleep(2); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    private void handleCallback(JsonObject callback) {
        String callbackId = callback.get("id").getAsString();
        JsonObject from = callback.getAsJsonObject("from");
        JsonObject message = callback.getAsJsonObject("message");
        String chatId = message.getAsJsonObject("chat").get("id").getAsString();
        String userId = from.get("id").getAsString();
        CallbackActionParser.ParsedAction action = CallbackActionParser.parse(callback.get("data").getAsString());
        if (action == null || !config.chatId().equals(chatId) || !config.allowedUserIds().contains(userId)) {
            api.post("answerCallbackQuery", "{\"callback_query_id\":\"" + json(callbackId) + "\",\"text\":\"" + json(Messages.get("provider.not_authorized_telegram")) + "\",\"show_alert\":true}");
            return;
        }
        api.post("answerCallbackQuery", "{\"callback_query_id\":\"" + json(callbackId) + "\"}");
        decisions.decide(action.requestId(), action.action(), new AdminPrincipal("telegram", userId,
                        from.has("username") ? from.get("username").getAsString() : userId), Optional.empty());
    }

    private static String render(RequestView request) {
        return ApprovalMessageRenderer.render(request);
    }

    private static String keyboard(UUID requestId, boolean disabled) {
        if (disabled) return "{\"inline_keyboard\":[]}";
        return "{\"inline_keyboard\":[[" +
                "{\"text\":\"✅ " + json(Messages.get("button.approve")) + "\",\"callback_data\":\"wr:a:" + requestId + "\"}," +
                "{\"text\":\"❌ " + json(Messages.get("button.deny")) + "\",\"callback_data\":\"wr:d:" + requestId + "\"}," +
                "{\"text\":\"🚫 " + json(Messages.get("button.block")) + "\",\"callback_data\":\"wr:b:" + requestId + "\"}]," +
                "[{\"text\":\"↩ " + json(Messages.get("button.undo")) + "\",\"callback_data\":\"wr:u:" + requestId + "\"}]]}";
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
