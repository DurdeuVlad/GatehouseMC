package com.gatehousemc.application.admin;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Loader-neutral control operations used by every administrative adapter. */
public interface RuntimeControlPort {
    CompletionStage<AdminCommandResult> reload();
    CompletionStage<AdminCommandResult> providerStatus(Optional<String> provider);
    CompletionStage<AdminCommandResult> providerTest(String provider);
}
