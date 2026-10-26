package com.gatehousemc.application.admin;

import java.util.Objects;

/** Canonical request-reference lookup contract used by all administrative adapters. */
@FunctionalInterface
public interface RequestResolver {
    RequestResolution resolve(RequestReference reference, ResolutionPurpose purpose);

    enum ResolutionPurpose {
        SHOW,
        APPROVE,
        DENY,
        BLOCK,
        REOPEN,
        UNDO
    }

    static RequestReference parseReference(String raw) {
        return RequestReference.parse(Objects.requireNonNull(raw, "raw"));
    }
}
