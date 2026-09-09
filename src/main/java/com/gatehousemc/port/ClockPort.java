package com.gatehousemc.port;

import java.time.Instant;

@FunctionalInterface
public interface ClockPort {
    Instant now();
}
