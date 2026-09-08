package com.gatehousemc.whitelistrequest.port;

import com.gatehousemc.whitelistrequest.domain.PublicationRef;
import com.gatehousemc.whitelistrequest.domain.RequestView;

import java.util.concurrent.CompletionStage;

public interface ApprovalInterface {
    String id();

    ProviderHealth health();

    CompletionStage<PublicationRef> publish(RequestView request);

    CompletionStage<Void> update(PublicationRef publication, RequestView request);

    void start();

    void stop();
}
