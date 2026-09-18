package com.gatehousemc.integration.telegram;

import com.gatehousemc.application.DecisionService;
import com.gatehousemc.config.ModConfig;
import com.gatehousemc.domain.*;
import com.gatehousemc.i18n.Messages;
import com.gatehousemc.integration.common.ApprovalActionState;
import com.gatehousemc.integration.common.ApprovalMessageRenderer;
import com.gatehousemc.integration.common.CallbackActionParser;
import com.gatehousemc.integration.common.CallbackActionParser.ParsedCallback;
import com.gatehousemc.integration.common.CallbackActionParser.CallbackKind;
import com.gatehousemc.port.ApprovalInterface;
import com.gatehousemc.port.ProviderHealth;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Telegram Bot API adapter using Java 21 HttpClient and a dedicated long-polling thread. */
public final class TelegramApprovalInterface implements ApprovalInterface {
    private static final Logger LOGGER = LoggerFactory.getLogger(TelegramApprovalInterface.class);

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
        String payload = "{\"chat_id\":\"" + json(config.chatId()) + "\",\"text\":\"" + json(render(request)) + "\",\"reply_markup\":" + keyboard(request.id(), request.status()) + "}";
        return current.post("sendMessage", payload).thenApply(body -> {
            health = ProviderHealth.HEALTHY;
            JsonObject message = body.getAsJsonObject("result");
            return new PublicationRef(id(), config.chatId(), message.get("message_id").getAsString());
        }).whenComplete((ignored, error) -> {
            if (error != null) health = ProviderHealth.DEGRADED;
        });
    }

    @Override
    public CompletionStage<Void> update(PublicationRef publication, RequestView request) {
        TelegramTransport current = api;
        if (current == null) return CompletableFuture.failedFuture(new IllegalStateException("Telegram is stopped"));
        String payload = "{\"chat_id\":\"" + json(publication.containerId()) + "\",\"message_id\":\"" + json(publication.messageId()) + "\",\"text\":\"" + json(render(request)) + "\",\"reply_markup\":" + keyboard(request.id(), request.status()) + "}";
        return current.post("editMessageText", payload).thenApply(ignored -> {
            health = ProviderHealth.HEALTHY;
            return (Void) null;
        }).whenComplete((ignored, error) -> {
            if (error != null) health = ProviderHealth.DEGRADED;
        });
    }

    private void pollLoop() {
        while (running) {
            try {
                String payload = "{\"timeout\":20,\"offset\":" + offset + ",\"allowed_updates\":[\"callback_query\"]}";
                JsonArray updates = api.post("getUpdates", payload).join().getAsJsonArray("result");
                if (health == ProviderHealth.DEGRADED) {
                    LOGGER.info("telegram.poll_recovered: Telegram polling recovered");
                }
                health = ProviderHealth.HEALTHY;
                updates.forEach(update -> {
                    JsonObject value = update.getAsJsonObject();
                    offset = Math.max(offset, value.get("update_id").getAsLong() + 1);
                    if (value.has("callback_query")) handleCallback(value.getAsJsonObject("callback_query"));
                });
            } catch (Exception error) {
                if (health != ProviderHealth.DEGRADED) {
                    LOGGER.warn("telegram.poll_failed: {}", safeMessage(error));
                }
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
        if (parsed == null) {
            answerCallback(callbackId, Messages.get("provider.invalid_action"), true);
            return;
        }
        if (!config.chatId().equals(chatId) || !config.allowedUserIds().contains(userId)) {
            answerCallback(callbackId, Messages.get("provider.not_authorized_telegram"), true);
            return;
        }
        AdminPrincipal principal = new AdminPrincipal("telegram", userId,
                from.has("username") ? from.get("username").getAsString() : userId);
        switch (parsed.kind()) {
            case PRIMARY -> {
                Optional<RequestView> request = decisions.findRequest(parsed.requestId());
                if (request.isEmpty()) {
                    answerCallback(callbackId, Messages.get("confirm.expired"), true);
                    return;
                }
                RequestView current = request.get();
                if (!ApprovalActionState.canExecute(current.status(), parsed.action())) {
                    answerCallback(callbackId, ApprovalActionState.unavailableMessage(current.status()), true);
                    return;
                }
                String player = current.identity().exactUsername();
                String shortId = parsed.requestId().toString().substring(0, 8);
                String confirmText = confirmMessage(parsed.action(), player, shortId, current.status());
                String confirmationKeyboard = confirmKeyboard(parsed.action(), parsed.requestId());
                answerCallback(callbackId, "", false);
                api.post("editMessageText", "{\"chat_id\":\"" + json(chatId) + "\",\"message_id\":\"" + json(messageId) + "\",\"text\":\"" + json(confirmText) + "\",\"reply_markup\":" + confirmationKeyboard + "}");
            }
            case CONFIRM -> {
                Optional<RequestView> request = decisions.findRequest(parsed.requestId());
                if (request.isEmpty()) {
                    answerCallback(callbackId, Messages.get("confirm.expired"), true);
                    return;
                }
                if (!ApprovalActionState.canExecute(request.get().status(), parsed.action())) {
                    answerCallback(callbackId, ApprovalActionState.unavailableMessage(request.get().status()), true);
                    return;
                }
                decisions.decide(parsed.requestId(), parsed.action(), principal, Optional.empty())
                        .whenComplete((result, error) -> {
                            if (error != null) {
                                answerCallback(callbackId, Messages.get("provider.decision_failed"), true);
                            } else {
                                answerCallback(callbackId, result.message(), false);
                            }
                        });
            }
            case CANCEL -> {
                Optional<RequestView> request = decisions.findRequest(parsed.requestId());
                if (request.isPresent()) {
                    answerCallback(callbackId, Messages.get("confirm.cancelled"), false);
                    String originalText = render(request.get());
                    String originalKeyboard = keyboard(parsed.requestId(), request.get().status());
                    api.post("editMessageText", "{\"chat_id\":\"" + json(chatId) + "\",\"message_id\":\"" + json(messageId) + "\",\"text\":\"" + json(originalText) + "\",\"reply_markup\":" + originalKeyboard + "}");
                } else {
                    answerCallback(callbackId, Messages.get("confirm.expired"), true);
                }
            }
        }
    }

    private static String confirmMessage(DecisionAction action, String player, String shortId, RequestStatus status) {
        return switch (action) {
            case APPROVE -> Messages.get("confirm.approve", player, shortId);
            case DENY -> Messages.get("confirm.deny", player, shortId);
            case BLOCK -> Messages.get("confirm.block", player, shortId);
            case UNDO -> switch (status) {
                case APPROVED -> Messages.get("confirm.undo_approval", player, shortId);
                case DENIED -> Messages.get("confirm.reopen", player, shortId);
                case BLOCKED -> Messages.get("confirm.unblock_reopen", player, shortId);
                default -> Messages.get("confirm.undo", player, shortId);
            };
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

    static String keyboard(UUID requestId, RequestStatus status) {
        return switch (status) {
            case PENDING -> "{\"inline_keyboard\":[[" +
                    buttonJson("✅", ApprovalActionState.label(status, DecisionAction.APPROVE),
                            CallbackActionParser.formatPrimary(DecisionAction.APPROVE, requestId)) + "," +
                    buttonJson("❌", ApprovalActionState.label(status, DecisionAction.DENY),
                            CallbackActionParser.formatPrimary(DecisionAction.DENY, requestId)) + "],[" +
                    buttonJson("🚫", ApprovalActionState.label(status, DecisionAction.BLOCK),
                            CallbackActionParser.formatPrimary(DecisionAction.BLOCK, requestId)) + "]]}";
            case APPROVED, DENIED, BLOCKED -> "{\"inline_keyboard\":[[" +
                    buttonJson("↩", ApprovalActionState.label(status, DecisionAction.UNDO),
                            CallbackActionParser.formatPrimary(DecisionAction.UNDO, requestId)) + "]]}";
            case RESOLVING -> "{\"inline_keyboard\":[]}";
        };
    }

    private static String buttonJson(String icon, String label, String callback) {
        return "{\"text\":\"" + icon + " " + json(label) + "\",\"callback_data\":\"" + json(callback) + "\"}";
    }

    private void answerCallback(String callbackId, String message, boolean alert) {
        String text = message == null || message.isBlank() ? "" : ",\"text\":\"" + json(message) + "\"";
        api.post("answerCallbackQuery", "{\"callback_query_id\":\"" + json(callbackId) + "\"" + text
                + (alert ? ",\"show_alert\":true" : "") + "}");
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static String safeMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
