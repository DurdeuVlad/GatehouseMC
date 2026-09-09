package com.gatehousemc.integration.telegram;

import com.gatehousemc.application.DecisionService;
import com.gatehousemc.config.ModConfig;
import com.gatehousemc.domain.*;
import com.gatehousemc.i18n.Messages;
import com.gatehousemc.integration.common.ApprovalMessageRenderer;
import com.gatehousemc.integration.common.CallbackActionParser;
import com.gatehousemc.integration.common.CallbackActionParser.ParsedCallback;
import com.gatehousemc.integration.common.CallbackActionParser.CallbackKind;
import com.gatehousemc.port.ApprovalInterface;
import com.gatehousemc.port.ProviderHealth;
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
        String messageId = message.get("message_id").getAsString();
        String userId = from.get("id").getAsString();
        ParsedCallback parsed = CallbackActionParser.parse(callback.get("data").getAsString());
        if (parsed == null || !config.chatId().equals(chatId) || !config.allowedUserIds().contains(userId)) {
            api.post("answerCallbackQuery", "{\"callback_query_id\":\"" + json(callbackId) + "\",\"text\":\"" + json(Messages.get("provider.not_authorized_telegram")) + "\",\"show_alert\":true}");
            return;
        }
        AdminPrincipal principal = new AdminPrincipal("telegram", userId,
                from.has("username") ? from.get("username").getAsString() : userId);
        switch (parsed.kind()) {
            case PRIMARY -> {
                Optional<RequestView> request = decisions.findRequest(parsed.requestId());
                String player = request.map(r -> r.identity().exactUsername()).orElse("?");
                String shortId = parsed.requestId().toString().substring(0, 8);
                String confirmText = confirmMessage(parsed.action(), player, shortId);
                String keyboard = confirmKeyboard(parsed.action(), parsed.requestId());
                api.post("answerCallbackQuery", "{\"callback_query_id\":\"" + json(callbackId) + "\"}");
                api.post("editMessageText", "{\"chat_id\":\"" + json(chatId) + "\",\"message_id\":\"" + json(messageId) + "\",\"text\":\"" + json(confirmText) + "\",\"reply_markup\":" + keyboard + "}");
            }
            case CONFIRM -> {
                api.post("answerCallbackQuery", "{\"callback_query_id\":\"" + json(callbackId) + "\"}");
                decisions.decide(parsed.requestId(), parsed.action(), principal, Optional.empty());
            }
            case CANCEL -> {
                Optional<RequestView> request = decisions.findRequest(parsed.requestId());
                api.post("answerCallbackQuery", "{\"callback_query_id\":\"" + json(callbackId) + "\"}");
                if (request.isPresent()) {
                    String originalText = render(request.get());
                    String originalKeyboard = keyboard(parsed.requestId(), request.get().status().isTerminal());
                    api.post("editMessageText", "{\"chat_id\":\"" + json(chatId) + "\",\"message_id\":\"" + json(messageId) + "\",\"text\":\"" + json(originalText) + "\",\"reply_markup\":" + originalKeyboard + "}");
                } else {
                    api.post("editMessageText", "{\"chat_id\":\"" + json(chatId) + "\",\"message_id\":\"" + json(messageId) + "\",\"text\":\"" + json(Messages.get("confirm.cancelled")) + "\"}");
                }
            }
        }
    }

    private static String confirmMessage(DecisionAction action, String player, String shortId) {
        return switch (action) {
            case APPROVE -> Messages.get("confirm.approve", player, shortId);
            case DENY -> Messages.get("confirm.deny", player, shortId);
            case BLOCK -> Messages.get("confirm.block", player, shortId);
            case UNDO -> Messages.get("confirm.undo", player, shortId);
        };
    }

    private static String confirmKeyboard(DecisionAction action, UUID requestId) {
        return "{\"inline_keyboard\":[[" +
                "{\"text\":\"✅ " + json(Messages.get("button.confirm")) + "\",\"callback_data\":\"" + json(CallbackActionParser.formatConfirm(action, requestId)) + "\"}," +
                "{\"text\":\"❌ " + json(Messages.get("button.cancel")) + "\",\"callback_data\":\"" + json(CallbackActionParser.formatCancel(requestId)) + "\"}]]}";
    }

    private static String render(RequestView request) {
        return ApprovalMessageRenderer.render(request);
    }

    private static String keyboard(UUID requestId, boolean disabled) {
        if (disabled) return "{\"inline_keyboard\":[]}";
        return "{\"inline_keyboard\":[[" +
                "{\"text\":\"✅ " + json(Messages.get("button.approve")) + "\",\"callback_data\":\"" + json(CallbackActionParser.formatPrimary(DecisionAction.APPROVE, requestId)) + "\"}," +
                "{\"text\":\"❌ " + json(Messages.get("button.deny")) + "\",\"callback_data\":\"" + json(CallbackActionParser.formatPrimary(DecisionAction.DENY, requestId)) + "\"}," +
                "{\"text\":\"🚫 " + json(Messages.get("button.block")) + "\",\"callback_data\":\"" + json(CallbackActionParser.formatPrimary(DecisionAction.BLOCK, requestId)) + "\"}]," +
                "[{\"text\":\"↩ " + json(Messages.get("button.undo")) + "\",\"callback_data\":\"" + json(CallbackActionParser.formatPrimary(DecisionAction.UNDO, requestId)) + "\"}]]}";
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
