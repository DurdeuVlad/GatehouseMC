package com.gatehousemc.whitelistrequest.port;

/**
 * Raised when the persistence layer cannot complete an operation due to a
 * storage failure (e.g. database is unreachable, corrupted, or locked).
 *
 * This is an unchecked exception because most callers operate on daemon
 * threads and cannot meaningfully recover from a storage outage — they
 * should let it propagate to the nearest error boundary, which logs and
 * degrades gracefully.
 */
public class StorageException extends RuntimeException {
    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public StorageException(String message) {
        super(message);
    }
}
