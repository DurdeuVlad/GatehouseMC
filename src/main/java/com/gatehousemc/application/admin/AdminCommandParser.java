package com.gatehousemc.application.admin;

import com.gatehousemc.domain.RequestStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Pure parser for the logical `/gatehouse` grammar. */
public final class AdminCommandParser {
    private static final int DEFAULT_PAGE = 1;
    /** Telegram accepts at most 4096 characters; keep every adapter bounded too. */
    public static final int MAX_INPUT_LENGTH = 4096;

    private AdminCommandParser() {}

    public static AdminCommandResult parse(String input) {
        if (input == null) return AdminCommandResult.error(AdminCommandResultCode.INVALID_ARGUMENT, "command is required");
        if (input.length() > MAX_INPUT_LENGTH) {
            return invalid("command is too long");
        }
        List<String> tokens = new ArrayList<>(Arrays.stream(input.trim().split("\\s+"))
                .filter(token -> !token.isBlank()).toList());
        if (!tokens.isEmpty() && tokens.get(0).equalsIgnoreCase("/gatehouse")) tokens.remove(0);
        if (tokens.isEmpty()) return AdminCommandResult.success(AdminCommand.help(null), "help");

        String root = tokens.remove(0).toLowerCase(Locale.ROOT);
        try {
            return switch (root) {
                case "help" -> help(tokens);
                case "requests" -> requests(tokens);
                case "show" -> requestAction(tokens, AdminCommand.Kind.SHOW, false);
                case "approve" -> requestAction(tokens, AdminCommand.Kind.APPROVE, true);
                case "deny" -> requestAction(tokens, AdminCommand.Kind.DENY, true);
                case "block" -> requestAction(tokens, AdminCommand.Kind.BLOCK, true);
                case "reopen" -> requestAction(tokens, AdminCommand.Kind.REOPEN, true);
                case "undo" -> requestAction(tokens, AdminCommand.Kind.UNDO, true);
                case "unblock" -> usernameAction(tokens, AdminCommand.Kind.UNBLOCK);
                case "status" -> exact(tokens, AdminCommand.base(AdminCommand.Kind.STATUS));
                case "reload" -> exact(tokens, AdminCommand.base(AdminCommand.Kind.RELOAD));
                case "provider" -> provider(tokens);
                case "setup" -> setup(tokens);
                case "admin" -> admin(tokens);
                default -> invalid("unknown command: " + root);
            };
        } catch (IllegalArgumentException error) {
            return invalid(error.getMessage());
        }
    }

    private static AdminCommandResult help(List<String> tokens) {
        if (tokens.size() > 1) return invalid("help accepts at most one topic");
        return AdminCommandResult.success(AdminCommand.help(tokens.isEmpty() ? null : tokens.get(0)), "help");
    }

    private static AdminCommandResult requests(List<String> tokens) {
        if (tokens.size() > 2) return invalid("requests accepts status and page");
        RequestStatus status = RequestStatus.PENDING;
        if (!tokens.isEmpty()) {
            String value = tokens.get(0).toUpperCase(Locale.ROOT);
            if (value.equals("ALL")) return requestsWithStatus(null, tokens);
            try {
                status = RequestStatus.valueOf(value);
            } catch (IllegalArgumentException error) {
                return invalid("invalid request status: " + tokens.get(0));
            }
            if (status == RequestStatus.RESOLVING) {
                // RESOLVING is an explicit diagnostic filter, but not a user-facing lifecycle alias.
                return requestsWithStatus(status, tokens);
            }
        }
        return requestsWithStatus(status, tokens);
    }

    private static AdminCommandResult requestsWithStatus(RequestStatus status, List<String> tokens) {
        int page = parsePage(tokens.size() < 2 ? null : tokens.get(1));
        AdminCommand command = AdminCommand.base(AdminCommand.Kind.REQUESTS).withStatus(status, page);
        return AdminCommandResult.success(command, "requests");
    }

    private static AdminCommandResult requestAction(List<String> tokens, AdminCommand.Kind kind, boolean reasonAllowed) {
        if (tokens.isEmpty()) return invalid(kind.name().toLowerCase(Locale.ROOT) + " requires a request reference");
        RequestReference reference = RequestReference.parse(tokens.get(0));
        String reason = joinReason(tokens, 1, reasonAllowed);
        if (!reasonAllowed && tokens.size() > 1) return invalid(kind.name().toLowerCase(Locale.ROOT) + " does not accept a reason");
        return AdminCommandResult.success(AdminCommand.base(kind).withRequest(reference, reason), kind.name().toLowerCase(Locale.ROOT));
    }

    private static AdminCommandResult usernameAction(List<String> tokens, AdminCommand.Kind kind) {
        if (tokens.isEmpty()) return invalid("unblock requires a username");
        return AdminCommandResult.success(AdminCommand.base(kind).withUsername(tokens.get(0), joinReason(tokens, 1, true)), "unblock");
    }

    private static AdminCommandResult provider(List<String> tokens) {
        if (tokens.isEmpty() || tokens.size() > 2) return invalid("provider requires status/test and an optional provider");
        String operation = tokens.get(0).toLowerCase(Locale.ROOT);
        if (!operation.equals("status") && !operation.equals("test")) return invalid("unknown provider operation: " + operation);
        if (operation.equals("test") && tokens.size() != 2) return invalid("provider test requires a provider");
        AdminCommand command = AdminCommand.base(operation.equals("status")
                ? AdminCommand.Kind.PROVIDER_STATUS : AdminCommand.Kind.PROVIDER_TEST);
        return AdminCommandResult.success(tokens.size() == 1 ? command : command.withProvider(providerName(tokens.get(1))), operation);
    }

    private static AdminCommandResult setup(List<String> tokens) {
        if (tokens.isEmpty()) return invalid("setup requires a subcommand");
        String operation = tokens.get(0).toLowerCase(Locale.ROOT);
        return switch (operation) {
            case "status" -> exact(tokens.subList(1, tokens.size()), AdminCommand.base(AdminCommand.Kind.SETUP_STATUS));
            case "discord", "telegram" -> exact(tokens.subList(1, tokens.size()),
                    AdminCommand.base(AdminCommand.Kind.SETUP_PROVIDER).withProvider(providerName(operation)));
            case "bind" -> {
                if (tokens.size() != 3) yield invalid("setup bind requires provider and code");
                yield AdminCommandResult.success(AdminCommand.base(AdminCommand.Kind.SETUP_BIND)
                        .withProvider(providerName(tokens.get(1))).withTopic(tokens.get(2)), "setup bind");
            }
            case "cancel" -> {
                if (tokens.size() != 2) yield invalid("setup cancel requires a provider");
                yield AdminCommandResult.success(AdminCommand.base(AdminCommand.Kind.SETUP_CANCEL)
                        .withProvider(providerName(tokens.get(1))), "setup cancel");
            }
            default -> invalid("unknown setup operation: " + operation);
        };
    }

    private static AdminCommandResult admin(List<String> tokens) {
        if (tokens.size() < 2) return invalid("admin requires list/add/remove and a provider");
        String operation = tokens.get(0).toLowerCase(Locale.ROOT);
        String provider = providerName(tokens.get(1));
        if (operation.equals("list")) return exact(tokens.subList(2, tokens.size()),
                AdminCommand.base(AdminCommand.Kind.ADMIN_LIST).withProvider(provider));
        if (!operation.equals("add") && !operation.equals("remove")) return invalid("unknown admin operation: " + operation);
        if (tokens.size() < 3) return invalid("admin " + operation + " requires a principal");
        AdminCommand command = AdminCommand.base(operation.equals("add") ? AdminCommand.Kind.ADMIN_ADD : AdminCommand.Kind.ADMIN_REMOVE)
                .withProvider(provider).withAdmin(tokens.get(2), tokens.size() > 3 ? parseCapability(tokens.get(3)) : null);
        if (operation.equals("add") && command.access().isEmpty()) return invalid("admin add requires view, decide, or manage");
        if (tokens.size() > (operation.equals("add") ? 4 : 3)) return invalid("too many admin arguments");
        return AdminCommandResult.success(command, "admin " + operation);
    }

    private static AdminCapability parseCapability(String value) {
        return AdminCapability.valueOf(value.toUpperCase(Locale.ROOT));
    }

    private static String providerName(String value) {
        String provider = value.toLowerCase(Locale.ROOT);
        if (!provider.equals("discord") && !provider.equals("telegram")) {
            throw new IllegalArgumentException("provider must be discord or telegram");
        }
        return provider;
    }

    private static AdminCommandResult exact(List<String> tokens, AdminCommand command) {
        return tokens.isEmpty() ? AdminCommandResult.success(command, command.kind().name().toLowerCase(Locale.ROOT))
                : invalid("unexpected arguments");
    }

    private static int parsePage(String value) {
        if (value == null) return DEFAULT_PAGE;
        try {
            int page = Integer.parseInt(value);
            if (page < 1) throw new IllegalArgumentException("page must be at least 1");
            return page;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("page must be a positive integer");
        }
    }

    private static String joinReason(List<String> tokens, int from, boolean allowed) {
        if (!allowed || tokens.size() <= from) return null;
        String reason = String.join(" ", tokens.subList(from, tokens.size())).trim();
        if (reason.length() > 500) throw new IllegalArgumentException("reason must be at most 500 characters");
        return reason;
    }

    private static AdminCommandResult invalid(String message) {
        return AdminCommandResult.error(AdminCommandResultCode.INVALID_ARGUMENT,
                message == null || message.isBlank() ? "invalid command" : message);
    }
}
