package com.gatehousemc.application.admin;

import com.gatehousemc.domain.RequestStatus;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Canonical parsed command model. Adapters render this model; they do not redefine it. */
public record AdminCommand(Kind kind, Optional<String> topic, Optional<RequestStatus> status,
                           int page, Optional<RequestReference> request, Optional<String> username,
                           Optional<String> reason, Optional<String> provider,
                           Optional<String> principal, Optional<AdminCapability> access) {
    public AdminCommand {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(access, "access");
        if (page < 0) throw new IllegalArgumentException("page must not be negative");
        reason = reason.map(String::trim).filter(value -> !value.isEmpty());
    }

    public enum Kind {
        HELP,
        REQUESTS,
        SHOW,
        APPROVE,
        DENY,
        BLOCK,
        REOPEN,
        UNDO,
        UNBLOCK,
        STATUS,
        RELOAD,
        PROVIDER_STATUS,
        PROVIDER_TEST,
        SETUP_STATUS,
        SETUP_PROVIDER,
        SETUP_BIND,
        SETUP_CANCEL,
        ADMIN_LIST,
        ADMIN_ADD,
        ADMIN_REMOVE
    }

    public static AdminCommand help(String topic) {
        return base(Kind.HELP).withTopic(topic);
    }

    public static AdminCommand base(Kind kind) {
        return new AdminCommand(kind, Optional.empty(), Optional.empty(), 0, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    public AdminCommand withTopic(String value) {
        return copy(Optional.ofNullable(value).map(String::trim).filter(v -> !v.isEmpty()), status, page,
                request, username, reason, provider, principal, access);
    }

    public AdminCommand withStatus(RequestStatus value, int pageNumber) {
        if (pageNumber < 1) throw new IllegalArgumentException("page must be at least 1");
        return copy(topic, Optional.ofNullable(value), pageNumber, request, username, reason, provider, principal, access);
    }

    public AdminCommand withRequest(RequestReference value, String valueReason) {
        return copy(topic, status, page, Optional.ofNullable(value), username,
                Optional.ofNullable(valueReason), provider, principal, access);
    }

    public AdminCommand withUsername(String value, String valueReason) {
        return copy(topic, status, page, request, Optional.ofNullable(value).map(v -> v.toLowerCase(Locale.ROOT)),
                Optional.ofNullable(valueReason), provider, principal, access);
    }

    public AdminCommand withProvider(String value) {
        return copy(topic, status, page, request, username, reason,
                Optional.ofNullable(value).map(v -> v.toLowerCase(Locale.ROOT)), principal, access);
    }

    public AdminCommand withAdmin(String value, AdminCapability valueAccess) {
        return copy(topic, status, page, request, username, reason, provider,
                Optional.ofNullable(value), Optional.ofNullable(valueAccess));
    }

    private AdminCommand copy(Optional<String> nextTopic, Optional<RequestStatus> nextStatus, int nextPage,
                               Optional<RequestReference> nextRequest, Optional<String> nextUsername,
                               Optional<String> nextReason, Optional<String> nextProvider,
                               Optional<String> nextPrincipal, Optional<AdminCapability> nextAccess) {
        return new AdminCommand(kind, nextTopic, nextStatus, nextPage, nextRequest, nextUsername,
                nextReason, nextProvider, nextPrincipal, nextAccess);
    }
}
