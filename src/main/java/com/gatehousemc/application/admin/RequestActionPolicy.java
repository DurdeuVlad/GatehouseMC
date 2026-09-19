package com.gatehousemc.application.admin;

import com.gatehousemc.domain.RequestStatus;

import java.util.EnumSet;
import java.util.Set;

/** Sole source of request-state action availability for every adapter. */
public final class RequestActionPolicy {
    private RequestActionPolicy() {}

    public static Set<RequestAction> allowed(RequestStatus status) {
        return switch (status) {
            case PENDING -> immutable(RequestAction.APPROVE, RequestAction.DENY, RequestAction.BLOCK);
            case RESOLVING -> Set.of();
            case APPROVED -> immutable(RequestAction.UNDO);
            case DENIED, BLOCKED -> immutable(RequestAction.REOPEN);
        };
    }

    public static boolean allows(RequestStatus status, RequestAction action) {
        return allowed(status).contains(action);
    }

    private static Set<RequestAction> immutable(RequestAction first, RequestAction... rest) {
        EnumSet<RequestAction> actions = EnumSet.of(first, rest);
        return Set.copyOf(actions);
    }
}
