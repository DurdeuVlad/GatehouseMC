package com.gatehousemc.application.admin;

import java.util.Objects;
import java.util.Optional;

/** Transport-neutral result returned by the shared administrative command service. */
public record AdminCommandResult(AdminCommandResultCode code, Optional<AdminCommand.Kind> kind,
                                 Optional<?> value, String message) {
    public AdminCommandResult {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(message, "message");
        if (message.isBlank()) throw new IllegalArgumentException("message must not be blank");
    }

    public boolean succeeded() {
        return code == AdminCommandResultCode.SUCCESS;
    }

    public static AdminCommandResult success(String message) {
        return new AdminCommandResult(AdminCommandResultCode.SUCCESS, Optional.empty(), Optional.empty(), message);
    }

    public static AdminCommandResult success(Object value, String message) {
        return new AdminCommandResult(AdminCommandResultCode.SUCCESS, Optional.empty(), Optional.ofNullable(value), message);
    }

    public static AdminCommandResult success(AdminCommand.Kind kind, Object value, String message) {
        return new AdminCommandResult(AdminCommandResultCode.SUCCESS, Optional.ofNullable(kind),
                Optional.ofNullable(value), message);
    }

    public static AdminCommandResult error(AdminCommandResultCode code, String message) {
        if (code == AdminCommandResultCode.SUCCESS) {
            throw new IllegalArgumentException("success requires success factory");
        }
        return new AdminCommandResult(code, Optional.empty(), Optional.empty(), message);
    }
}
