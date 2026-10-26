package com.gatehousemc.application.admin;

import com.gatehousemc.config.ModConfig;
import com.gatehousemc.domain.WhitelistRequest;
import com.gatehousemc.i18n.Messages;

import java.util.List;

/**
 * Canonical operator-facing rendering for {@link AdminCommandResult} payloads.
 *
 * <p>Every transport surface — the Fabric, Forge, and NeoForge command adapters
 * plus the Discord and Telegram interfaces — must render command results through
 * this class. Rendering is owned here, once, so the list/show output cannot
 * drift between loaders or silently drop the result payload (the regression that
 * hid runtime-created requests from operators on every non-Fabric surface).</p>
 */
public final class AdminResultRenderer {
    private AdminResultRenderer() {}

    /** Renders the full operator-facing text for a command result. */
    public static String render(AdminCommandResult result) {
        if (result == null || result.value().isEmpty()) {
            return result == null ? "" : result.message();
        }
        Object value = result.value().orElseThrow();
        if (value instanceof WhitelistRequest request) {
            return result.message() + ": " + requestDetail(request);
        }
        if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                return result.message() + ": " + emptyText(result.kind());
            }
            StringBuilder output = new StringBuilder(result.message());
            for (Object item : list) {
                output.append('\n').append(row(item));
            }
            return output.toString();
        }
        return result.message() + ": " + value;
    }

    private static String emptyText(java.util.Optional<AdminCommand.Kind> kind) {
        return kind.filter(value -> value == AdminCommand.Kind.ADMIN_LIST).isPresent()
                ? Messages.get("command.no_entries")
                : Messages.get("command.no_requests");
    }

    /** One list row; every element type {@link AdminCommandService} can emit is handled. */
    private static String row(Object item) {
        if (item instanceof WhitelistRequest request) {
            return shortId(request) + " " + request.status() + " "
                    + request.identity().exactUsername() + " attempts=" + request.attemptCount();
        }
        if (item instanceof ModConfig.Principal principal) {
            return principal.kind() + ":" + principal.id() + " (" + principal.access() + ")";
        }
        return String.valueOf(item);
    }

    /**
     * The single-request format used by show/decision feedback: the full request
     * id is included so operators can copy it into follow-up commands.
     */
    private static String requestDetail(WhitelistRequest request) {
        return request.id() + " " + request.status() + " player=" + request.identity().exactUsername()
                + " uuid=" + request.identity().offlineUuid() + " attempts=" + request.attemptCount();
    }

    private static String shortId(WhitelistRequest request) {
        return request.id().toString().substring(0, 8);
    }
}
