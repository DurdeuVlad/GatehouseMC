package com.gatehousemc.application.admin;

import com.gatehousemc.port.ApprovalInterface;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Runtime provider/status implementation shared by the command adapters. */
public final class ProviderRuntimeControl implements RuntimeControlPort {
    private final List<ApprovalInterface> providers;
    private final AtomicReference<Supplier<CompletionStage<AdminCommandResult>>> reloadHandler = new AtomicReference<>();

    public ProviderRuntimeControl(List<ApprovalInterface> providers) {
        this.providers = Objects.requireNonNull(providers, "providers");
    }

    public void setReloadHandler(Supplier<CompletionStage<AdminCommandResult>> handler) {
        reloadHandler.set(handler);
    }

    @Override
    public CompletionStage<AdminCommandResult> reload() {
        Supplier<CompletionStage<AdminCommandResult>> handler = reloadHandler.get();
        if (handler != null) return handler.get();
        return CompletableFuture.completedFuture(AdminCommandResult.error(AdminCommandResultCode.UNAVAILABLE,
                "Reload is controlled by the active Minecraft loader and is unavailable in this runtime."));
    }

    @Override
    public CompletionStage<AdminCommandResult> providerStatus(Optional<String> requested) {
        if (requested.isPresent()) {
            ApprovalInterface provider = find(requested.orElseThrow());
            if (provider == null) return completedMissing(requested.orElseThrow());
            return CompletableFuture.completedFuture(AdminCommandResult.success(
                    provider.health(), provider.id() + "=" + provider.health()
                            + " destination=" + provider.configuredDestination()
                            + (provider.lastActionableError().isBlank() ? "" : " problem=" + provider.lastActionableError())));
        }
        String message = providers.stream().map(provider -> provider.id() + "=" + provider.health())
                .reduce((left, right) -> left + " " + right).orElse("no providers configured");
        return CompletableFuture.completedFuture(AdminCommandResult.success(message));
    }

    @Override
    public CompletionStage<AdminCommandResult> providerTest(String providerName) {
        ApprovalInterface provider = find(providerName);
        if (provider == null) return completedMissing(providerName);
        return provider.testDelivery().handle((ignored, error) -> {
            if (error != null) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                return AdminCommandResult.error(AdminCommandResultCode.FAILED,
                        "Provider test failed for " + provider.id() + ": "
                                + (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()));
            }
            return AdminCommandResult.success("Provider test delivered to " + provider.id());
        });
    }

    private ApprovalInterface find(String value) {
        String normalized = Objects.requireNonNull(value, "provider").trim().toLowerCase(Locale.ROOT);
        return providers.stream().filter(provider -> provider.id().equals(normalized)).findFirst().orElse(null);
    }

    private static CompletionStage<AdminCommandResult> completedMissing(String provider) {
        return CompletableFuture.completedFuture(AdminCommandResult.error(AdminCommandResultCode.NOT_FOUND,
                "Provider is not configured: " + provider));
    }
}
