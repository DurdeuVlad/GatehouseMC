package com.gatehousemc.port;

import com.gatehousemc.domain.PublicationRef;
import com.gatehousemc.domain.RequestView;

import java.util.concurrent.CompletionStage;

public interface ApprovalInterface {
    String id();

    ProviderHealth health();

    CompletionStage<PublicationRef> publish(RequestView request);

    CompletionStage<Void> update(PublicationRef publication, RequestView request);

    void start();

    void stop();
}
