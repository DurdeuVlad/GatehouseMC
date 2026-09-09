package com.gatehousemc.whitelistrequest.integration.common;

import com.gatehousemc.whitelistrequest.domain.DecisionAction;

import java.util.UUID;

/**
 * Shared parser for the {@code wr:<a|d|b>:<uuid>} callback format used by all approval interfaces.
 * Transport adapters delegate here so the parsing logic is not duplicated.
 */
public final class CallbackActionParser {
    private CallbackActionParser() {}

    /**
     * Parses a callback value of the form {@code wr:<a|d|b>:<uuid>}.
     *
     * @return the parsed action, or {@code null} if the value is malformed.
     */
    public static ParsedAction parse(String value) {
        if (value == null || !value.startsWith("wr:") || value.length() < 5) return null;
        String[] parts = value.split(":", 3);
        if (parts.length != 3) return null;
        try {
            DecisionAction action = switch (parts[1]) {
                case "a" -> DecisionAction.APPROVE;
                case "d" -> DecisionAction.DENY;
                case "b" -> DecisionAction.BLOCK;
                default -> null;
            };
            return action == null ? null : new ParsedAction(action, UUID.fromString(parts[2]));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /** The parsed result of a callback action. */
    public static class ParsedAction {
        private final DecisionAction action;
        private final UUID requestId;

        public ParsedAction(DecisionAction action, UUID requestId) {
            this.action = action;
            this.requestId = requestId;
        }

        public DecisionAction action() { return action; }

        public UUID requestId() { return requestId; }
    }
}
