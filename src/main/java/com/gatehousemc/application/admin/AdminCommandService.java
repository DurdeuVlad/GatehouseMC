package com.gatehousemc.application.admin;

import com.gatehousemc.application.DecisionService;
import com.gatehousemc.domain.AdminPrincipal;
import com.gatehousemc.domain.DecisionAction;
import com.gatehousemc.domain.DecisionOutcome;
import com.gatehousemc.domain.RequestStatus;
import com.gatehousemc.domain.WhitelistRequest;
import com.gatehousemc.port.WorkflowRepository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Canonical application owner for all textual administrative commands. */
public final class AdminCommandService {
    private static final int PAGE_SIZE = 10;

    private final WorkflowRepository repository;
    private final DecisionService decisions;
    private final RequestResolver resolver;
    private final AdminAuthorizationService authorization;
    private final ProviderPrincipalRegistry principals;
    private final SetupService setup;
    private final RuntimeControlPort runtimeControl;

    public AdminCommandService(WorkflowRepository repository, DecisionService decisions,
                               RequestResolver resolver, AdminAuthorizationService authorization) {
        this(repository, decisions, resolver, authorization, null, null, null);
    }

    public AdminCommandService(WorkflowRepository repository, DecisionService decisions,
                               RequestResolver resolver, AdminAuthorizationService authorization,
                               ProviderPrincipalRegistry principals) {
        this(repository, decisions, resolver, authorization, principals, null, null);
    }

    public AdminCommandService(WorkflowRepository repository, DecisionService decisions,
                               RequestResolver resolver, AdminAuthorizationService authorization,
                               ProviderPrincipalRegistry principals, SetupService setup) {
        this(repository, decisions, resolver, authorization, principals, setup, null);
    }

    public AdminCommandService(WorkflowRepository repository, DecisionService decisions,
                               RequestResolver resolver, AdminAuthorizationService authorization,
                               ProviderPrincipalRegistry principals, SetupService setup,
                               RuntimeControlPort runtimeControl) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.principals = principals;
        this.setup = setup;
        this.runtimeControl = runtimeControl;
    }

    public CompletionStage<AdminCommandResult> execute(AdminCommand command, AdminPrincipal actor) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(actor, "actor");
        AdminCommandResult access = authorization.authorize(actor, command);
        if (!access.succeeded()) return CompletableFuture.completedFuture(access);
        try {
            return switch (command.kind()) {
                case HELP -> complete(AdminCommandResult.success(command, "Gatehouse administration: /gatehouse help"));
                case REQUESTS -> complete(requests(command));
                case SHOW -> complete(show(command));
                case APPROVE, DENY, BLOCK, REOPEN, UNDO -> decide(command, actor);
                case UNBLOCK -> complete(unblock(command, actor));
                case STATUS -> complete(status());
                case RELOAD -> runtimeControl == null ? complete(unavailable()) : runtimeControl.reload();
                case PROVIDER_STATUS -> runtimeControl == null ? complete(unavailable())
                        : runtimeControl.providerStatus(command.provider());
                case PROVIDER_TEST -> runtimeControl == null ? complete(unavailable())
                        : runtimeControl.providerTest(command.provider().orElseThrow());
                case ADMIN_LIST -> complete(adminList(command));
                case ADMIN_ADD -> complete(adminAdd(command, actor));
                case ADMIN_REMOVE -> complete(adminRemove(command, actor));
                case SETUP_STATUS -> complete(setup == null ? unavailable() : setup.status());
                case SETUP_PROVIDER -> complete(setup == null ? unavailable() : setup.issue(command.provider().orElseThrow()));
                case SETUP_BIND -> complete(setup == null ? unavailable() : setup.bind(command.provider().orElseThrow(), command.topic().orElseThrow()));
                case SETUP_CANCEL -> complete(setup == null ? unavailable() : setup.cancel(command.provider().orElseThrow()));
                default -> complete(AdminCommandResult.error(AdminCommandResultCode.UNAVAILABLE,
                        "This Gatehouse command is not available in the current runtime."));
            };
        } catch (SecurityException error) {
            return complete(AdminCommandResult.error(AdminCommandResultCode.UNAUTHORIZED, error.getMessage()));
        } catch (RuntimeException error) {
            return complete(AdminCommandResult.error(AdminCommandResultCode.FAILED,
                    "Gatehouse command failed: " + error.getClass().getSimpleName()));
        }
    }

    private AdminCommandResult requests(AdminCommand command) {
        int limit = Math.multiplyExact(command.page(), PAGE_SIZE);
        List<WhitelistRequest> all = repository.findByStatus(command.status(), limit);
        int from = Math.min((command.page() - 1) * PAGE_SIZE, all.size());
        int to = Math.min(from + PAGE_SIZE, all.size());
        return AdminCommandResult.success(List.copyOf(all.subList(from, to)), "Requests page " + command.page());
    }

    private AdminCommandResult show(AdminCommand command) {
        RequestResolution resolution = resolver.resolve(command.request().orElseThrow(), RequestResolver.ResolutionPurpose.SHOW);
        return resultForResolution(resolution, true);
    }

    private CompletionStage<AdminCommandResult> decide(AdminCommand command, AdminPrincipal actor) {
        RequestResolver.ResolutionPurpose purpose = RequestResolver.ResolutionPurpose.valueOf(command.kind().name());
        RequestResolution resolution = resolver.resolve(command.request().orElseThrow(), purpose);
        if (resolution.outcome() != RequestResolution.Outcome.FOUND) {
            return complete(resultForResolution(resolution, false));
        }
        WhitelistRequest request = resolution.request().orElseThrow();
        Optional<String> reason = command.reason();
        CompletionStage<com.gatehousemc.domain.DecisionResult> result;
        if (command.kind() == AdminCommand.Kind.REOPEN) {
            result = decisions.reopen(request.id(), actor, reason);
        } else {
            DecisionAction action = command.kind() == AdminCommand.Kind.UNDO
                    ? DecisionAction.UNDO : DecisionAction.valueOf(command.kind().name());
            result = decisions.decide(request.id(), action, actor, reason);
        }
        return result.handle((decision, error) -> {
            if (error != null) return AdminCommandResult.error(AdminCommandResultCode.FAILED, "Gatehouse decision failed");
            return decisionResult(decision);
        });
    }

    private AdminCommandResult unblock(AdminCommand command, AdminPrincipal actor) {
        String username = command.username().orElseThrow();
        boolean removed = decisions.unblock(username, actor, command.reason().orElse(""));
        return removed ? AdminCommandResult.success("Unblocked " + username)
                : AdminCommandResult.error(AdminCommandResultCode.NOT_FOUND, "No active block exists for " + username);
    }

    private AdminCommandResult status() {
        try {
            long outbox = repository.pendingOutboxCount();
            int requests = repository.findByStatus(Optional.empty(), 500).size();
            return AdminCommandResult.success("health=HEALTHY requests=" + requests + " outbox=" + outbox);
        } catch (RuntimeException error) {
            return AdminCommandResult.error(AdminCommandResultCode.UNAVAILABLE,
                    "Gatehouse status unavailable: " + error.getClass().getSimpleName());
        }
    }

    private AdminCommandResult adminList(AdminCommand command) {
        if (principals == null) return unavailable();
        return AdminCommandResult.success(principals.list(command.provider().orElseThrow()),
                "Configured principals for " + command.provider().orElseThrow());
    }

    private AdminCommandResult adminAdd(AdminCommand command, AdminPrincipal actor) {
        if (principals == null) return unavailable();
        return AdminCommandResult.success(principals.add(actor, command.provider().orElseThrow(),
                command.principal().orElseThrow(), command.access().orElseThrow()));
    }

    private AdminCommandResult adminRemove(AdminCommand command, AdminPrincipal actor) {
        if (principals == null) return unavailable();
        return AdminCommandResult.success(principals.remove(actor, command.provider().orElseThrow(),
                command.principal().orElseThrow()));
    }

    private static AdminCommandResult unavailable() {
        return AdminCommandResult.error(AdminCommandResultCode.UNAVAILABLE,
                "This Gatehouse command is not available in the current runtime.");
    }

    private AdminCommandResult resultForResolution(RequestResolution resolution, boolean show) {
        return switch (resolution.outcome()) {
            case FOUND -> AdminCommandResult.success(resolution.request().orElseThrow(), resolution.message());
            case NOT_FOUND -> AdminCommandResult.error(AdminCommandResultCode.NOT_FOUND, resolution.message());
            case AMBIGUOUS -> AdminCommandResult.error(AdminCommandResultCode.INVALID_ARGUMENT, resolution.message());
            case ACTIVE_CONFLICT -> AdminCommandResult.error(AdminCommandResultCode.CONFLICT, resolution.message());
            case INVALID_STATE -> AdminCommandResult.error(AdminCommandResultCode.INVALID_STATE, resolution.message());
        };
    }

    private AdminCommandResult decisionResult(com.gatehousemc.domain.DecisionResult result) {
        AdminCommandResultCode code = switch (result.outcome()) {
            case APPROVED, DENIED, BLOCKED, UNDONE -> AdminCommandResultCode.SUCCESS;
            case ALREADY_PENDING, ALREADY_RESOLVED, RESOLVING -> AdminCommandResultCode.INVALID_STATE;
            case CONFLICT -> AdminCommandResultCode.CONFLICT;
            case NOT_FOUND -> AdminCommandResultCode.NOT_FOUND;
            case FAILED -> AdminCommandResultCode.FAILED;
        };
        return new AdminCommandResult(code, result.request(), result.message());
    }

    private static CompletionStage<AdminCommandResult> complete(AdminCommandResult result) {
        return CompletableFuture.completedFuture(result);
    }
}
