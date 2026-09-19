package com.gatehousemc.application.admin;

import com.gatehousemc.domain.AdminPrincipal;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Resolves the single capability required by each canonical command. */
public final class AdminAuthorizationService {
    private final Function<AdminPrincipal, Optional<AdminCapability>> capabilityLookup;

    public AdminAuthorizationService(Function<AdminPrincipal, Optional<AdminCapability>> capabilityLookup) {
        this.capabilityLookup = Objects.requireNonNull(capabilityLookup, "capabilityLookup");
    }

    public AdminCommandResult authorize(AdminPrincipal actor, AdminCommand command) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(command, "command");
        AdminCapability required = requiredCapability(command.kind());
        Optional<AdminCapability> actual = capabilityLookup.apply(actor);
        if (actual.isPresent() && actual.get().includes(required)) return AdminCommandResult.success("authorized");
        return AdminCommandResult.error(AdminCommandResultCode.UNAUTHORIZED,
                "This Gatehouse action requires " + required + " access.");
    }

    public static AdminCapability requiredCapability(AdminCommand.Kind kind) {
        return switch (kind) {
            case APPROVE, DENY, BLOCK, REOPEN, UNDO, UNBLOCK -> AdminCapability.DECIDE;
            case RELOAD, PROVIDER_TEST, SETUP_STATUS, SETUP_PROVIDER, SETUP_BIND, SETUP_CANCEL,
                    ADMIN_LIST, ADMIN_ADD, ADMIN_REMOVE -> AdminCapability.MANAGE;
            case HELP, REQUESTS, SHOW, STATUS, PROVIDER_STATUS -> AdminCapability.VIEW;
        };
    }
}
