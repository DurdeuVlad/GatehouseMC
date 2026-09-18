package com.gatehousemc.runtime;

/** Result returned to a command source after an asynchronous configuration reload. */
public record ReloadResult(Status status, String detail) {
    public enum Status {
        SUCCESS,
        RESTART_REQUIRED,
        FAILED,
        UNAVAILABLE
    }

    public static ReloadResult success() {
        return new ReloadResult(Status.SUCCESS, "");
    }

    public static ReloadResult restartRequired() {
        return new ReloadResult(Status.RESTART_REQUIRED, "");
    }

    public static ReloadResult failed(String detail) {
        return new ReloadResult(Status.FAILED, detail == null || detail.isBlank() ? "unknown error" : detail);
    }

    public static ReloadResult unavailable(String detail) {
        return new ReloadResult(Status.UNAVAILABLE, detail == null || detail.isBlank() ? "runtime unavailable" : detail);
    }
}
