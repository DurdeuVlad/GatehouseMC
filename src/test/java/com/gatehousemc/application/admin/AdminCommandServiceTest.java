package com.gatehousemc.application.admin;

import com.gatehousemc.application.DecisionService;
import com.gatehousemc.application.RequestAdmissionCache;
import com.gatehousemc.application.WhitelistRequestService;
import com.gatehousemc.domain.AdminPrincipal;
import com.gatehousemc.domain.PlayerIdentity;
import com.gatehousemc.domain.RequestStatus;
import com.gatehousemc.domain.WhitelistRequest;
import com.gatehousemc.persistence.sqlite.SqliteDatabase;
import com.gatehousemc.persistence.sqlite.SqliteWorkflowRepository;
import com.gatehousemc.port.VanillaWhitelistPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminCommandServiceTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void unauthorizedDecisionHasZeroMutation(@TempDir Path temp) throws Exception {
        try (SqliteDatabase database = new SqliteDatabase(temp.resolve("admin-auth.sqlite"), 5000);
             SqliteWorkflowRepository repository = new SqliteWorkflowRepository(database)) {
            RequestAdmissionCache cache = new RequestAdmissionCache(() -> NOW);
            WhitelistRequestService requests = new WhitelistRequestService(repository, () -> NOW, Duration.ZERO, cache);
            requests.recordAttempt(PlayerIdentity.of("UnauthAdmin"));
            CountingWhitelist whitelist = new CountingWhitelist();
            AdminCommandService commands = service(repository, cache, whitelist,
                    new AdminAuthorizationService(actor -> Optional.of(AdminCapability.VIEW)));

            AdminCommand command = command("approve UnauthAdmin");
            AdminCommandResult result = commands.execute(command, new AdminPrincipal("discord", "42", "Viewer"))
                    .toCompletableFuture().join();

            assertEquals(AdminCommandResultCode.UNAUTHORIZED, result.code());
            assertEquals(RequestStatus.PENDING, repository.findActiveByName("unauthadmin").orElseThrow().status());
            assertEquals(0, whitelist.addCalls.get());
        }
    }

    @Test
    void sharedServiceExecutesApprovalAndReturnsDurableRequest(@TempDir Path temp) throws Exception {
        try (SqliteDatabase database = new SqliteDatabase(temp.resolve("admin-approve.sqlite"), 5000);
             SqliteWorkflowRepository repository = new SqliteWorkflowRepository(database)) {
            RequestAdmissionCache cache = new RequestAdmissionCache(() -> NOW);
            WhitelistRequestService requests = new WhitelistRequestService(repository, () -> NOW, Duration.ZERO, cache);
            requests.recordAttempt(PlayerIdentity.of("SharedAdmin"));
            CountingWhitelist whitelist = new CountingWhitelist();
            AdminCommandService commands = service(repository, cache, whitelist,
                    new AdminAuthorizationService(actor -> Optional.of(AdminCapability.MANAGE)));

            AdminCommandResult result = commands.execute(command("approve SharedAdmin reason from command"),
                            AdminPrincipal.console()).toCompletableFuture().join();

            assertEquals(AdminCommandResultCode.SUCCESS, result.code(), result.message());
            WhitelistRequest request = repository.findLatestByName("sharedadmin").orElseThrow();
            assertEquals(RequestStatus.APPROVED, request.status());
            assertEquals(1, whitelist.addCalls.get());
            assertTrue(result.value().orElseThrow() instanceof WhitelistRequest);
        }
    }

    @Test
    void invalidCurrentStateReturnsActionableGuidance(@TempDir Path temp) throws Exception {
        try (SqliteDatabase database = new SqliteDatabase(temp.resolve("admin-state.sqlite"), 5000);
             SqliteWorkflowRepository repository = new SqliteWorkflowRepository(database)) {
            RequestAdmissionCache cache = new RequestAdmissionCache(() -> NOW);
            WhitelistRequestService requests = new WhitelistRequestService(repository, () -> NOW, Duration.ZERO, cache);
            requests.recordAttempt(PlayerIdentity.of("StatefulAdmin"));
            var requestId = repository.findActiveByName("statefuladmin").orElseThrow().id();
            DecisionService decisions = new DecisionService(repository, new CountingWhitelist(), () -> NOW, cache);
            decisions.decide(requestId, com.gatehousemc.domain.DecisionAction.DENY,
                    AdminPrincipal.console(), Optional.empty()).toCompletableFuture().join();
            AdminCommandService commands = new AdminCommandService(repository, decisions,
                    new DefaultRequestResolver(repository),
                    new AdminAuthorizationService(actor -> Optional.of(AdminCapability.MANAGE)));

            AdminCommandResult result = commands.execute(command("approve StatefulAdmin"), AdminPrincipal.console())
                    .toCompletableFuture().join();

            assertEquals(AdminCommandResultCode.INVALID_STATE, result.code());
            assertTrue(result.message().contains("reopen"));
            assertEquals(RequestStatus.DENIED, repository.findById(requestId).orElseThrow().status());
        }
    }

    /**
     * Regression for the reported operator-facing defect: a request created at
     * runtime after a vanilla whitelist rejection must appear in
     * {@code /gatehouse requests} immediately — no reload or restart may be
     * required — and the canonical rendering must contain the row, not just a
     * bare header or count.
     */
    @Test
    void runtimeCreatedRequestAppearsInRequestsListImmediately(@TempDir Path temp) throws Exception {
        try (SqliteDatabase database = new SqliteDatabase(temp.resolve("admin-list.sqlite"), 5000);
             SqliteWorkflowRepository repository = new SqliteWorkflowRepository(database)) {
            RequestAdmissionCache cache = new RequestAdmissionCache(() -> NOW);
            WhitelistRequestService requests = new WhitelistRequestService(repository, () -> NOW, Duration.ZERO, cache);
            AdminCommandService commands = service(repository, cache, new CountingWhitelist(),
                    new AdminAuthorizationService(actor -> Optional.of(AdminCapability.VIEW)));

            assertEquals(com.gatehousemc.domain.AttemptState.CREATED,
                    requests.recordAttempt(PlayerIdentity.of("RuntimeNewcomer")).state());

            AdminCommandResult result = commands.execute(command("requests"), AdminPrincipal.console())
                    .toCompletableFuture().join();

            assertEquals(AdminCommandResultCode.SUCCESS, result.code(), result.message());
            Object value = result.value().orElseThrow();
            assertTrue(value instanceof List<?>);
            List<?> listed = (List<?>) value;
            assertEquals(1, listed.size());
            WhitelistRequest listedRequest = (WhitelistRequest) listed.get(0);
            assertEquals("RuntimeNewcomer", listedRequest.identity().exactUsername());
            assertEquals(RequestStatus.PENDING, listedRequest.status());

            String rendered = AdminResultRenderer.render(result);
            assertTrue(rendered.contains("RuntimeNewcomer"), rendered);
            assertTrue(rendered.contains(listedRequest.id().toString().substring(0, 8)), rendered);
        }
    }

    @Test
    void requestsListReflectsDecisionChangesImmediately(@TempDir Path temp) throws Exception {
        try (SqliteDatabase database = new SqliteDatabase(temp.resolve("admin-list-fresh.sqlite"), 5000);
             SqliteWorkflowRepository repository = new SqliteWorkflowRepository(database)) {
            RequestAdmissionCache cache = new RequestAdmissionCache(() -> NOW);
            WhitelistRequestService requests = new WhitelistRequestService(repository, () -> NOW, Duration.ZERO, cache);
            requests.recordAttempt(PlayerIdentity.of("StayPending"));
            requests.recordAttempt(PlayerIdentity.of("GetsDenied"));
            DecisionService decisions = new DecisionService(repository, new CountingWhitelist(), () -> NOW, cache);
            AdminCommandService commands = new AdminCommandService(repository, decisions,
                    new DefaultRequestResolver(repository),
                    new AdminAuthorizationService(actor -> Optional.of(AdminCapability.MANAGE)));

            commands.execute(command("deny GetsDenied"), AdminPrincipal.console()).toCompletableFuture().join();

            AdminCommandResult pending = commands.execute(command("requests"), AdminPrincipal.console())
                    .toCompletableFuture().join();
            List<?> pendingRows = (List<?>) pending.value().orElseThrow();
            assertEquals(1, pendingRows.size());
            assertEquals("StayPending", ((WhitelistRequest) pendingRows.get(0)).identity().exactUsername());

            AdminCommandResult denied = commands.execute(command("requests denied"), AdminPrincipal.console())
                    .toCompletableFuture().join();
            List<?> deniedRows = (List<?>) denied.value().orElseThrow();
            assertEquals(1, deniedRows.size());
            assertEquals("GetsDenied", ((WhitelistRequest) deniedRows.get(0)).identity().exactUsername());

            AdminCommandResult all = commands.execute(command("requests all"), AdminPrincipal.console())
                    .toCompletableFuture().join();
            assertEquals(2, ((List<?>) all.value().orElseThrow()).size());
        }
    }

    private static AdminCommandService service(SqliteWorkflowRepository repository, RequestAdmissionCache cache,
                                               CountingWhitelist whitelist, AdminAuthorizationService authorization) {
        DecisionService decisions = new DecisionService(repository, whitelist, () -> NOW, cache);
        return new AdminCommandService(repository, decisions, new DefaultRequestResolver(repository), authorization);
    }

    private static AdminCommand command(String value) {
        AdminCommandResult result = AdminCommandParser.parse(value);
        assertEquals(AdminCommandResultCode.SUCCESS, result.code(), result.message());
        return (AdminCommand) result.value().orElseThrow();
    }

    private static final class CountingWhitelist implements VanillaWhitelistPort {
        final AtomicInteger addCalls = new AtomicInteger();

        @Override public CompletableFuture<Boolean> isWhitelisted(PlayerIdentity identity) { return CompletableFuture.completedFuture(false); }
        @Override public CompletableFuture<Void> addExactProfile(PlayerIdentity identity) { addCalls.incrementAndGet(); return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<Void> removeExactProfile(PlayerIdentity identity) { return CompletableFuture.completedFuture(null); }
    }
}
