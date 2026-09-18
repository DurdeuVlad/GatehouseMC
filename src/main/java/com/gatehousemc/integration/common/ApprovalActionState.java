package com.gatehousemc.integration.common;

import com.gatehousemc.domain.DecisionAction;
import com.gatehousemc.domain.RequestStatus;
import com.gatehousemc.i18n.Messages;

import java.util.Objects;

/** Shared UX rules for which approval actions are meaningful in each request state. */
public final class ApprovalActionState {
    private ApprovalActionState() {}

    public static boolean canExecute(RequestStatus status, DecisionAction action) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(action, "action");
        return switch (status) {
            case PENDING -> action == DecisionAction.APPROVE
                    || action == DecisionAction.DENY
                    || action == DecisionAction.BLOCK;
            case RESOLVING -> false;
            case APPROVED, DENIED, BLOCKED -> action == DecisionAction.UNDO;
        };
    }

    public static String label(RequestStatus status, DecisionAction action) {
        return switch (action) {
            case APPROVE -> Messages.get("button.approve");
            case DENY -> Messages.get("button.deny");
            case BLOCK -> Messages.get("button.block");
            case UNDO -> switch (status) {
                case APPROVED -> Messages.get("button.undo_approval");
                case DENIED -> Messages.get("button.reopen");
                case BLOCKED -> Messages.get("button.unblock_reopen");
                default -> Messages.get("button.undo");
            };
        };
    }

    public static String unavailableMessage(RequestStatus status) {
        return Messages.get("provider.action_unavailable", status.name().toLowerCase());
    }
}
