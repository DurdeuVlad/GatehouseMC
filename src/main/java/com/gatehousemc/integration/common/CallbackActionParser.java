package com.gatehousemc.integration.common;

import com.gatehousemc.domain.DecisionAction;

import java.util.UUID;

/**
 * Shared parser for callback formats used by all approval interfaces.
 * <p>
 * Primary action format: {@code wr:<a|d|b|u>:<uuid>}
 * Confirm action format: {@code wr:c:<a|d|b|u>:<uuid>}
 * Cancel action format: {@code wr:x:<uuid>}
 * <p>
 * Transport adapters delegate here so the parsing logic is not duplicated.
 */
public final class CallbackActionParser {
    private CallbackActionParser() {}

    /**
     * Parses a callback value and returns either a primary action or a confirm/cancel directive.
     *
     * @return the parsed callback, or {@code null} if the value is malformed.
     */
    public static ParsedCallback parse(String value) {
        if (value == null || !value.startsWith("wr:") || value.length() < 5) return null;
        String[] parts = value.split(":", 4);
        if (parts.length < 3) return null;
        try {
            String kind = parts[1];
            return switch (kind) {
                case "a", "d", "b", "u" -> {
                    DecisionAction action = actionCode(kind);
                    yield new ParsedCallback(CallbackKind.PRIMARY, action, UUID.fromString(parts[2]));
                }
                case "c" -> {
                    if (parts.length != 4) yield null;
                    DecisionAction action = actionCode(parts[2]);
                    if (action == null) yield null;
                    yield new ParsedCallback(CallbackKind.CONFIRM, action, UUID.fromString(parts[3]));
                }
                case "x" -> new ParsedCallback(CallbackKind.CANCEL, null, UUID.fromString(parts[2]));
                default -> null;
            };
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /**
     * Legacy parse method that returns only primary actions, ignoring confirm/cancel.
     * Kept for backward compatibility with existing tests.
     */
    public static ParsedAction parseAction(String value) {
        ParsedCallback callback = parse(value);
        if (callback == null || callback.kind() != CallbackKind.PRIMARY) return null;
        return new ParsedAction(callback.action(), callback.requestId());
    }

    private static DecisionAction actionCode(String code) {
        return switch (code) {
            case "a" -> DecisionAction.APPROVE;
            case "d" -> DecisionAction.DENY;
            case "b" -> DecisionAction.BLOCK;
            case "u" -> DecisionAction.UNDO;
            default -> null;
        };
    }

    /** Builds a primary action callback string. */
    public static String formatPrimary(DecisionAction action, UUID requestId) {
        return "wr:" + code(action) + ":" + requestId;
    }

    /** Builds a confirm callback string. */
    public static String formatConfirm(DecisionAction action, UUID requestId) {
        return "wr:c:" + code(action) + ":" + requestId;
    }

    /** Builds a cancel callback string. */
    public static String formatCancel(UUID requestId) {
        return "wr:x:" + requestId;
    }

    private static String code(DecisionAction action) {
        return switch (action) {
            case APPROVE -> "a";
            case DENY -> "d";
            case BLOCK -> "b";
            case UNDO -> "u";
        };
    }

    /** The kind of callback received. */
    public enum CallbackKind { PRIMARY, CONFIRM, CANCEL }

    /** The parsed result of a callback. */
    public static class ParsedCallback {
        private final CallbackKind kind;
        private final DecisionAction action;
        private final UUID requestId;

        public ParsedCallback(CallbackKind kind, DecisionAction action, UUID requestId) {
            this.kind = kind;
            this.action = action;
            this.requestId = requestId;
        }

        public CallbackKind kind() { return kind; }
        public DecisionAction action() { return action; }
        public UUID requestId() { return requestId; }
    }

    /** Legacy parsed action for backward compatibility. */
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
