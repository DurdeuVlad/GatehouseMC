package com.gatehousemc.integration.telegram;

import com.gatehousemc.application.DecisionService;
import com.gatehousemc.application.ConfirmationStore;
import com.gatehousemc.application.ExternalCommandRateLimiter;
import com.gatehousemc.application.admin.RequestAction;
import com.gatehousemc.application.admin.RequestActionPolicy;
import com.gatehousemc.application.admin.AdminCommand;
import com.gatehousemc.application.admin.AdminCommandService;
import com.gatehousemc.application.admin.RequestReference;
import com.gatehousemc.application.admin.AdminCommandParser;
import com.gatehousemc.application.admin.AdminCommandResult;
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
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Telegram Bot API adapter using Java 21 HttpClient and a dedicated long-polling thread. */
public final class TelegramApprovalInterface implements ApprovalInterface {
    private final ModConfig.Telegram config;
    private final DecisionService decisions;
    private final ConfirmationStore confirmations;
    private final AdminCommandService adminCommands;
    private final ExternalCommandRateLimiter commandRateLimiter;
    private volatile ProviderHealth health = ProviderHealth.STOPPED;
    private volatile TelegramTransport api;
    private volatile ExecutorService poller;
    private volatile boolean running;
    private volatile long offset;
    private volatile String lastSuccessfulOperation = "";
    private volatile String lastActionableError = "";

    public TelegramApprovalInterface(ModConfig.Telegram config, DecisionService decisions) {
        this.config = config;
        this.decisions = decisions;
        this.confirmations = new ConfirmationStore(Instant::now);
        this.adminCommands = null;
        this.commandRateLimiter = new ExternalCommandRateLimiter(Instant::now);
    }

    /** Package-private constructor for fake-transport testing. */
    TelegramApprovalInterface(ModConfig.Telegram config, DecisionService decisions, TelegramTransport transport) {
        this(config, decisions, transport, new ConfirmationStore(Instant::now));
    }

    TelegramApprovalInterface(ModConfig.Telegram config, DecisionService decisions,
                              TelegramTransport transport, ConfirmationStore confirmations) {
        this(config, decisions, transport, confirmations, null);
    }

    public TelegramApprovalInterface(ModConfig.Telegram config, DecisionService decisions,
                                     TelegramTransport transport, AdminCommandService adminCommands) {
        this(config, decisions, transport, new ConfirmationStore(Instant::now), adminCommands);
    }

    private TelegramApprovalInterface(ModConfig.Telegram config, DecisionService decisions,
                                      TelegramTransport transport, ConfirmationStore confirmations,
                                      AdminCommandService adminCommands) {
        this.config = config;
        this.decisions = decisions;
        this.confirmations = confirmations;
        this.adminCommands = adminCommands;
        this.commandRateLimiter = new ExternalCommandRateLimiter(Instant::now);
        this.api = transport;
        this.health = ProviderHealth.HEALTHY;
    }

    @Override
    public String id() { return "telegram"; }

    @Override
    public ProviderHealth health() { return health; }

    @Override
    public String configuredDestination() { return config.chatId().isBlank() ? "unconfigured" : "Telegram chat " + config.chatId(); }
    @Override
    public String lastSuccessfulOperation() { return lastSuccessfulOperation; }
    @Override
    public String lastActionableError() { return lastActionableError; }

    @Override
    public CompletionStage<Void> testDelivery() {
        TelegramTransport current = api;
        if (current == null) return CompletableFuture.failedFuture(new IllegalStateException("Telegram is unavailable"));
        String payload = "{\"chat_id\":\"" + json(config.chatId())
                + "\",\"text\":\"GatehouseMC provider test — delivery is working.\"}";
        return current.post("sendMessage", payload)
                .thenRun(() -> { lastSuccessfulOperation = "provider test"; lastActionableError = ""; })
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        lastActionableError = safeError(error);
                        health = ProviderHealth.DEGRADED;
                    }
                });
    }

    @Override
    public void start() {
        if (!config.enabled()) {
            health = ProviderHealth.DISABLED;
            lastActionableError = "provider is intentionally disabled";
            return;
        }
        if (config.token() == null || config.token().isBlank() || config.chatId().isBlank()
                || (config.principals().isEmpty() && config.allowedUserIds().isEmpty())) {
            health = ProviderHealth.SETUP_REQUIRED;
            lastActionableError = "configure the Telegram token, chat, and at least one administrator principal";
            return;
        }
        api = new TelegramApiClient(config.token());
        api.post("setMyCommands", "{\"commands\":[{\"command\":\"gatehouse\",\"description\":\"Gatehouse administration and whitelist requests\"}]}")
                .thenRun(() -> lastSuccessfulOperation = "setMyCommands")
                .exceptionally(error -> { health = ProviderHealth.DEGRADED; lastActionableError = safeError(error); return null; });
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
            lastSuccessfulOperation = "publish";
            lastActionableError = "";
            return new PublicationRef(id(), config.chatId(), message.get("message_id").getAsString());
        }).whenComplete((ignored, error) -> { if (error != null) { health = ProviderHealth.DEGRADED; lastActionableError = safeError(error); } });
    }

    @Override
    public CompletionStage<Void> update(PublicationRef publication, RequestView request) {
        TelegramTransport current = api;
        if (current == null) return CompletableFuture.failedFuture(new IllegalStateException("Telegram is stopped"));
        String payload = "{\"chat_id\":\"" + json(publication.containerId()) + "\",\"message_id\":\"" + json(publication.messageId()) + "\",\"text\":\"" + json(render(request)) + "\",\"reply_markup\":" + keyboard(request.id(), request.status().isTerminal()) + "}";
        return current.post("editMessageText", payload).thenApply(ignored -> {
            lastSuccessfulOperation = "update";
            lastActionableError = "";
            return null;
        }).thenApply(ignored -> (Void) null)
                .whenComplete((ignored, error) -> { if (error != null) { health = ProviderHealth.DEGRADED; lastActionableError = safeError(error); } });
    }

    private void pollLoop() {
        while (running) {
            try {
                String payload = "{\"timeout\":20,\"offset\":" + offset + ",\"allowed_updates\":[\"callback_query\",\"message\"]}";
                JsonArray updates = api.post("getUpdates", payload).join().getAsJsonArray("result");
                health = ProviderHealth.HEALTHY;
                updates.forEach(update -> {
                    JsonObject value = update.getAsJsonObject();
                    offset = Math.max(offset, value.get("update_id").getAsLong() + 1);
                    if (value.has("callback_query")) handleCallback(value.getAsJsonObject("callback_query"));
                    if (value.has("message")) handleMessage(value.getAsJsonObject("message"));
                });
            } catch (Exception error) {
                health = ProviderHealth.DEGRADED;
                lastActionableError = safeError(error);
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
        if (parsed == null || !config.chatId().equals(chatId) || !authorizedUser(userId)) {
            api.post("answerCallbackQuery", "{\"callback_query_id\":\"" + json(callbackId) + "\",\"text\":\"" + json(Messages.get("provider.not_authorized_telegram")) + "\",\"show_alert\":true}");
            return;
        }
        ExternalCommandRateLimiter.Decision callbackRate = commandRateLimiter.allow("telegram", userId, true);
        if (!callbackRate.allowed()) {
            api.post("answerCallbackQuery", "{\"callback_query_id\":\"" + json(callbackId)
                    + "\",\"text\":\"Rate limited; retry shortly.\",\"show_alert\":true}");
            return;
        }
        AdminPrincipal principal = new AdminPrincipal("telegram", userId,
                from.has("username") ? from.get("username").getAsString() : userId);
        switch (parsed.kind()) {
            case PRIMARY -> {
                Optional<RequestView> request = decisions.findRequest(parsed.requestId());
                if (request.isEmpty()) {
                    api.post("answerCallbackQuery", "{\"callback_query_id\":\"" + json(callbackId) + "\",\"text\":\"" + json(Messages.get("command.request_not_found")) + "\",\"show_alert\":true}");
                    return;
                }
                RequestView current = request.get();
                RequestAction requestedAction = requestAction(parsed.action(), current.status());
                if (!RequestActionPolicy.allows(current.status(), requestedAction)) {
                    api.post("answerCallbackQuery", "{\"callback_query_id\":\"" + json(callbackId) + "\",\"text\":\"" + json(Messages.get("decision.already_resolved")) + "\",\"show_alert\":true}");
                    return;
                }
                ConfirmationStore.Confirmation confirmation = confirmations.issue(
                        principal.provider(), principal.externalId(), current.id(), requestedAction, current.status());
                String player = current.identity().exactUsername();
                String shortId = current.id().toString().substring(0, 8);
                String confirmText = confirmMessage(parsed.action(), player, shortId);
                String keyboard = confirmKeyboard(confirmation.token());
                api.post("answerCallbackQuery", "{\"callback_query_id\":\"" + json(callbackId) + "\"}");
                api.post("editMessageText", "{\"chat_id\":\"" + json(chatId) + "\",\"message_id\":\"" + json(messageId) + "\",\"text\":\"" + json(confirmText) + "\",\"reply_markup\":" + keyboard + "}");
            }
            case CONFIRM -> {
                api.post("answerCallbackQuery", "{\"callback_query_id\":\"" + json(callbackId) + "\"}");
                if (parsed.token() == null) {
                    decisions.decide(parsed.requestId(), parsed.action(), principal, Optional.empty());
                    return;
                }
                ConfirmationStore.Confirmation pending = confirmations.find(parsed.token()).orElse(null);
                if (pending == null) {
                    api.post("editMessageText", "{\"chat_id\":\"" + json(chatId) + "\",\"message_id\":\"" + json(messageId) + "\",\"text\":\"" + json(Messages.get("confirm.expired")) + "\"}");
                    return;
                }
                Optional<RequestView> current = decisions.findRequest(pending.requestId());
                ConfirmationStore.ConsumeResult consumed = confirmations.consume(parsed.token(), principal.provider(),
                        principal.externalId(), current.map(RequestView::status).orElse(null));
                if (consumed.status() != ConfirmationStore.ConsumeStatus.CONSUMED) {
                    String text = confirmationFailure(consumed.status(), current);
                    api.post("editMessageText", "{\"chat_id\":\"" + json(chatId) + "\",\"message_id\":\"" + json(messageId) + "\",\"text\":\"" + json(text) + "\"}");
                    return;
                }
                executeConfirmation(pending, principal)
                        .thenAccept(result -> api.post("editMessageText", "{\"chat_id\":\"" + json(chatId) + "\",\"message_id\":\"" + json(messageId) + "\",\"text\":\"" + json(result.message()) + "\"}"));
            }
            case CANCEL -> {
                api.post("answerCallbackQuery", "{\"callback_query_id\":\"" + json(callbackId) + "\"}");
                UUID requestId = parsed.requestId();
                if (parsed.token() != null) {
                    ConfirmationStore.ConsumeResult cancelled = confirmations.cancel(parsed.token(), principal.provider(), principal.externalId());
                    if (cancelled.status() != ConfirmationStore.ConsumeStatus.CONSUMED) {
                        api.post("editMessageText", "{\"chat_id\":\"" + json(chatId) + "\",\"message_id\":\"" + json(messageId) + "\",\"text\":\"" + json(confirmationFailure(cancelled.status(), Optional.empty())) + "\"}");
                        return;
                    }
                    requestId = cancelled.confirmation().orElseThrow().requestId();
                }
                Optional<RequestView> request = requestId == null ? Optional.empty() : decisions.findRequest(requestId);
                if (request.isPresent()) {
                    String originalText = render(request.get());
                    String originalKeyboard = keyboard(request.get().id(), request.get().status().isTerminal());
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

    private void handleMessage(JsonObject message) {
        if (!message.has("text") || !message.has("chat") || !message.has("from")) return;
        String chatId = message.getAsJsonObject("chat").get("id").getAsString();
        if (!config.chatId().equals(chatId)) return;
        JsonObject from = message.getAsJsonObject("from");
        String userId = from.get("id").getAsString();
        if (!authorizedUser(userId)) return;
        Optional<String> commandText = parseGatehouseMessage(message.get("text").getAsString());
        if (commandText.isEmpty() || adminCommands == null) return;
        AdminCommandResult parsed = AdminCommandParser.parse(commandText.get());
        if (!parsed.succeeded() || parsed.value().isEmpty() || !(parsed.value().get() instanceof AdminCommand command)) {
            sendText(chatId, parsed.message());
            return;
        }
        ExternalCommandRateLimiter.Decision commandRate = commandRateLimiter.allow("telegram", userId, isMutating(command));
        if (!commandRate.allowed()) {
            sendText(chatId, "Rate limited; retry in " + Math.max(1, commandRate.retryAfter().toSeconds()) + "s.");
            return;
        }
        AdminPrincipal principal = new AdminPrincipal("telegram", userId,
                from.has("username") ? from.get("username").getAsString() : userId);
        adminCommands.execute(command, principal)
                .thenAccept(result -> sendText(chatId, result.message()));
    }

    private static boolean isMutating(AdminCommand command) {
        return switch (command.kind()) {
            case APPROVE, DENY, BLOCK, REOPEN, UNDO, UNBLOCK, RELOAD, PROVIDER_TEST,
                    SETUP_PROVIDER, SETUP_BIND, SETUP_CANCEL, ADMIN_ADD, ADMIN_REMOVE -> true;
            default -> false;
        };
    }

    private boolean authorizedUser(String userId) {
        return config.principals().isEmpty()
                ? config.allowedUserIds().contains(userId)
                : config.principals().stream().anyMatch(principal -> principal.kind().equals("USER")
                && principal.id().equals(userId));
    }

    static Optional<String> parseGatehouseMessage(String text) {
        if (text == null) return Optional.empty();
        String value = text.trim();
        if (value.equals("/gatehouse")) return Optional.of("");
        if (value.startsWith("/gatehouse@")) {
            int space = value.indexOf(' ');
            return Optional.of(space < 0 ? "" : value.substring(space + 1).trim());
        }
        if (value.startsWith("/gatehouse ")) return Optional.of(value.substring("/gatehouse ".length()).trim());
        return Optional.empty();
    }

    private void sendText(String chatId, String text) {
        api.post("sendMessage", "{\"chat_id\":\"" + json(chatId) + "\",\"text\":\"" + json(text) + "\"}");
    }

    private static String confirmKeyboard(String token) {
        return "{\"inline_keyboard\":[[" +
                "{\"text\":\"✅ " + json(Messages.get("button.confirm")) + "\",\"callback_data\":\"" + json(CallbackActionParser.formatConfirmToken(token)) + "\"}," +
                "{\"text\":\"❌ " + json(Messages.get("button.cancel")) + "\",\"callback_data\":\"" + json(CallbackActionParser.formatCancelToken(token)) + "\"}]]}";
    }

    private static RequestAction requestAction(DecisionAction action, RequestStatus status) {
        if (action == DecisionAction.UNDO) return status == RequestStatus.APPROVED ? RequestAction.UNDO : RequestAction.REOPEN;
        return switch (action) {
            case APPROVE -> RequestAction.APPROVE;
            case DENY -> RequestAction.DENY;
            case BLOCK -> RequestAction.BLOCK;
            case UNDO -> throw new IllegalStateException();
        };
    }

    private static DecisionAction toDecisionAction(RequestAction action) {
        return switch (action) {
            case APPROVE -> DecisionAction.APPROVE;
            case DENY -> DecisionAction.DENY;
            case BLOCK -> DecisionAction.BLOCK;
            case REOPEN, UNDO -> DecisionAction.UNDO;
        };
    }

    private CompletionStage<AdminCommandResultView> executeConfirmation(ConfirmationStore.Confirmation confirmation,
                                                                           AdminPrincipal principal) {
        if (adminCommands == null) {
            return decisions.decide(confirmation.requestId(), toDecisionAction(confirmation.action()), principal, Optional.empty())
                    .thenApply(result -> new AdminCommandResultView(result.message()));
        }
        AdminCommand.Kind kind = switch (confirmation.action()) {
            case APPROVE -> AdminCommand.Kind.APPROVE;
            case DENY -> AdminCommand.Kind.DENY;
            case BLOCK -> AdminCommand.Kind.BLOCK;
            case REOPEN -> AdminCommand.Kind.REOPEN;
            case UNDO -> AdminCommand.Kind.UNDO;
        };
        AdminCommand command = AdminCommand.base(kind).withRequest(RequestReference.parse(confirmation.requestId().toString()), null);
        return adminCommands.execute(command, principal).thenApply(result -> new AdminCommandResultView(result.message()));
    }

    private record AdminCommandResultView(String message) {}

    private static String confirmationFailure(ConfirmationStore.ConsumeStatus status, Optional<RequestView> current) {
        return switch (status) {
            case EXPIRED -> Messages.get("confirm.expired");
            case STALE, MISSING_REQUEST -> current.map(TelegramApprovalInterface::render)
                    .orElse(Messages.get("command.request_not_found"));
            case WRONG_ACTOR, WRONG_PROVIDER, MISSING, CONSUMED -> Messages.get("provider.not_authorized_telegram");
        };
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

    private String safeError(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        return config.token() == null || config.token().isBlank() ? message : message.replace(config.token(), "[redacted]");
    }
}
