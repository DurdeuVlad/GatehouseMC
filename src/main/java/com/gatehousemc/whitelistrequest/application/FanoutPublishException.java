package com.gatehousemc.whitelistrequest.application;

import com.gatehousemc.whitelistrequest.domain.PublicationRef;

import java.util.List;

/**
 * Signals that a FANOUT publication was only partially successful.
 *
 * <p>The successful references must be persisted before the outbox event is
 * retried; otherwise a healthy provider can receive duplicate request
 * messages on every retry.</p>
 */
public final class FanoutPublishException extends RuntimeException {
    private final List<PublicationRef> successfulPublications;

    public FanoutPublishException(List<PublicationRef> successfulPublications, Throwable cause) {
        super("One or more approval providers failed during fanout publication", cause);
        this.successfulPublications = List.copyOf(successfulPublications);
    }

    public List<PublicationRef> successfulPublications() {
        return successfulPublications;
    }
}
