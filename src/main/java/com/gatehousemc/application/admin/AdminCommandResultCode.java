package com.gatehousemc.application.admin;

public enum AdminCommandResultCode {
    SUCCESS,
    INVALID_ARGUMENT,
    UNAUTHORIZED,
    NOT_FOUND,
    INVALID_STATE,
    CONFLICT,
    RATE_LIMITED,
    UNAVAILABLE,
    FAILED
}
