package com.gatehousemc.application.admin;

/** Mutating actions exposed by the request lifecycle command contract. */
public enum RequestAction {
    APPROVE,
    DENY,
    BLOCK,
    REOPEN,
    UNDO
}
