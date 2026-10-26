package com.gatehousemc.integration.telegram;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramApiClientTest {

    @Test
    void closeCancelsInFlightRequests() {
        BlockingHttpClient httpClient = new BlockingHttpClient();
        TelegramApiClient client = new TelegramApiClient("token", httpClient);

        CompletableFuture<JsonObject> response = client.post("getUpdates", "{}");
        client.close();

        assertTrue(httpClient.pending.isCancelled());
        CompletionException error = assertThrows(CompletionException.class, response::join);
        assertTrue(error.getCause() instanceof CancellationException);
        CompletionException closed = assertThrows(CompletionException.class,
                () -> client.post("getUpdates", "{}").join());
        assertTrue(closed.getCause() instanceof IllegalStateException);
    }

    private static final class BlockingHttpClient extends HttpClient {
        private final CompletableFuture<HttpResponse<String>> pending = new CompletableFuture<>();

        @Override
        public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }

        @Override
        public Optional<Duration> connectTimeout() { return Optional.empty(); }

        @Override
        public Redirect followRedirects() { return Redirect.NEVER; }

        @Override
        public Optional<ProxySelector> proxy() { return Optional.empty(); }

        @Override
        public SSLContext sslContext() { return null; }

        @Override
        public SSLParameters sslParameters() { return new SSLParameters(); }

        @Override
        public Optional<Authenticator> authenticator() { return Optional.empty(); }

        @Override
        public Version version() { return Version.HTTP_1_1; }

        @Override
        public Optional<Executor> executor() { return Optional.empty(); }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException();
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            return (CompletableFuture<HttpResponse<T>>) (CompletableFuture<?>) pending;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, responseBodyHandler);
        }

        @Override
        public WebSocket.Builder newWebSocketBuilder() {
            throw new UnsupportedOperationException();
        }
    }
}
