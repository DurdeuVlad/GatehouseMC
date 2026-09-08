package com.gatehousemc.whitelistrequest.port;

import java.time.Instant;

@FunctionalInterface
public interface ClockPort {
    Instant now();
}
