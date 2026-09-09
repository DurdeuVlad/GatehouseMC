package com.gatehousemc.application;

import java.time.Instant;

public record AdmissionState(Kind kind, Instant until) {
    public enum Kind { UNKNOWN, PENDING, DENIED, BLOCKED, DEGRADED }

    public static AdmissionState unknown() { return new AdmissionState(Kind.UNKNOWN, Instant.MIN); }
    public static AdmissionState pending() { return new AdmissionState(Kind.PENDING, Instant.MAX); }
    public static AdmissionState deniedUntil(Instant until) { return new AdmissionState(Kind.DENIED, until); }
    public static AdmissionState blocked() { return new AdmissionState(Kind.BLOCKED, Instant.MAX); }
    public static AdmissionState degraded() { return new AdmissionState(Kind.DEGRADED, Instant.MAX); }
}
