package com.gatehousemc.application.admin;

import com.gatehousemc.domain.RequestStatus;
import com.gatehousemc.domain.WhitelistRequest;
import com.gatehousemc.port.WorkflowRepository;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** SQLite-backed implementation of the canonical request-reference rules. */
public final class DefaultRequestResolver implements RequestResolver {
    private final WorkflowRepository repository;

    public DefaultRequestResolver(WorkflowRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public RequestResolution resolve(RequestReference reference, ResolutionPurpose purpose) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(purpose, "purpose");
        return switch (reference.kind()) {
            case UUID -> resolveOne(repository.findById(UUID.fromString(reference.value())), purpose, reference.value());
            case UUID_PREFIX -> resolvePrefix(reference.value(), purpose);
            case USERNAME -> resolveUsername(reference, purpose);
        };
    }

    private RequestResolution resolvePrefix(String prefix, ResolutionPurpose purpose) {
        List<WhitelistRequest> matches = repository.findByIdPrefix(prefix);
        if (matches.isEmpty()) return notFound("No request matches " + prefix + ".");
        if (matches.size() > 1) {
            return new RequestResolution(RequestResolution.Outcome.AMBIGUOUS, Optional.empty(), Optional.empty(),
                    "Request reference " + prefix + " is ambiguous; enter more characters or the full UUID.");
        }
        return resolveOne(Optional.of(matches.get(0)), purpose, prefix);
    }

    private RequestResolution resolveUsername(RequestReference reference, ResolutionPurpose purpose) {
        String username = reference.normalizedUsername();
        if (purpose == ResolutionPurpose.SHOW) {
            return resolveOne(repository.findLatestByName(username), purpose, reference.value());
        }
        if (purpose == ResolutionPurpose.APPROVE || purpose == ResolutionPurpose.DENY || purpose == ResolutionPurpose.BLOCK) {
            Optional<WhitelistRequest> active = repository.findActiveByName(username);
            return resolveOne(active.isPresent() ? active : repository.findLatestByName(username), purpose, reference.value());
        }

        Optional<WhitelistRequest> active = repository.findActiveByName(username);
        if (active.isPresent()) {
            return new RequestResolution(RequestResolution.Outcome.ACTIVE_CONFLICT, Optional.empty(), active,
                    "Cannot " + purpose.name().toLowerCase(Locale.ROOT) + " " + reference.value()
                            + ": active request " + shortId(active.get()) + " already exists. Resolve the active request first.");
        }
        Set<RequestStatus> states = purpose == ResolutionPurpose.REOPEN
                ? EnumSet.of(RequestStatus.DENIED, RequestStatus.BLOCKED)
                : EnumSet.of(RequestStatus.APPROVED);
        List<WhitelistRequest> matches = repository.findLatestByNameAndStatuses(username, states, 1);
        return resolveOne(matches.isEmpty() ? repository.findLatestByName(username) : Optional.of(matches.get(0)), purpose, reference.value());
    }

    private RequestResolution resolveOne(Optional<WhitelistRequest> request, ResolutionPurpose purpose, String reference) {
        if (request.isEmpty()) return notFound("No request matches " + reference + ".");
        WhitelistRequest value = request.get();
        if (purpose == ResolutionPurpose.SHOW) return found(value);
        RequestAction action = actionFor(purpose);
        if (RequestActionPolicy.allows(value.status(), action)) return found(value);
        return new RequestResolution(RequestResolution.Outcome.INVALID_STATE, Optional.of(value), Optional.empty(),
                invalidStateMessage(value, action));
    }

    private static RequestAction actionFor(ResolutionPurpose purpose) {
        return RequestAction.valueOf(purpose.name());
    }

    private static String invalidStateMessage(WhitelistRequest request, RequestAction action) {
        String username = request.identity().exactUsername();
        String id = shortId(request);
        return switch (action) {
            case APPROVE, DENY, BLOCK -> "Cannot " + action.name().toLowerCase(Locale.ROOT) + " " + username
                    + " because request " + id + " is " + request.status()
                    + ". Use /gatehouse reopen " + username + " first when applicable.";
            case REOPEN -> "Cannot reopen " + username + " because request " + id + " is " + request.status() + ".";
            case UNDO -> "Cannot undo " + username + " because request " + id + " is " + request.status()
                    + ". Undo is valid only for APPROVED requests.";
        };
    }

    private static RequestResolution found(WhitelistRequest request) {
        return new RequestResolution(RequestResolution.Outcome.FOUND, Optional.of(request), Optional.empty(), "Request found");
    }

    private static RequestResolution notFound(String message) {
        return new RequestResolution(RequestResolution.Outcome.NOT_FOUND, Optional.empty(), Optional.empty(), message);
    }

    private static String shortId(WhitelistRequest request) {
        return request.id().toString().substring(0, 8);
    }
}
