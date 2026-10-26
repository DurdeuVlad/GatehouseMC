package com.gatehousemc.application.admin;

import com.gatehousemc.domain.RequestStatus;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AdminCommandContractTest {
    @Test
    void emptyRootAndHelpShareTheSameCanonicalCommand() {
        AdminCommand root = command("");
        AdminCommand help = command("/gatehouse help");
        assertEquals(AdminCommand.Kind.HELP, root.kind());
        assertEquals(root, help);
    }

    @Test
    void grammarParsesRequestsDefaultsAndReason() {
        AdminCommand requests = command("requests denied 3");
        assertEquals(AdminCommand.Kind.REQUESTS, requests.kind());
        assertEquals(RequestStatus.DENIED, requests.status().orElseThrow());
        assertEquals(3, requests.page());

        AdminCommand deny = command("deny 88e56047 explain the mismatch");
        assertEquals(AdminCommand.Kind.DENY, deny.kind());
        assertEquals(RequestReference.Kind.UUID_PREFIX, deny.request().orElseThrow().kind());
        assertEquals("explain the mismatch", deny.reason().orElseThrow());
    }

    @Test
    void invalidStatusAndPageAreErrorsRatherThanAll() {
        AdminCommandResult invalidStatus = AdminCommandParser.parse("requests maybe");
        assertEquals(AdminCommandResultCode.INVALID_ARGUMENT, invalidStatus.code());
        AdminCommandResult invalidPage = AdminCommandParser.parse("requests pending 0");
        assertEquals(AdminCommandResultCode.INVALID_ARGUMENT, invalidPage.code());
    }

    @Test
    void commandInputHasAnExplicitTransportBound() {
        AdminCommandResult result = AdminCommandParser.parse("x".repeat(AdminCommandParser.MAX_INPUT_LENGTH + 1));
        assertEquals(AdminCommandResultCode.INVALID_ARGUMENT, result.code());
        assertEquals("command is too long", result.message());
    }

    @Test
    void allCommandFamiliesUseTheCanonicalRootModel() {
        assertEquals(AdminCommand.Kind.REOPEN, command("reopen Alice reason").kind());
        assertEquals(AdminCommand.Kind.UNDO, command("undo Alice").kind());
        assertEquals(AdminCommand.Kind.PROVIDER_TEST, command("provider test discord").kind());
        assertEquals(AdminCommand.Kind.SETUP_BIND, command("setup bind telegram 123456").kind());
        assertEquals(AdminCommand.Kind.ADMIN_ADD, command("admin add discord 42 manage").kind());
        assertTrue(command("provider status").provider().isEmpty());
    }

    @Test
    void capabilityHierarchyAndActionMatrixAreFixed() {
        assertTrue(AdminCapability.MANAGE.includes(AdminCapability.VIEW));
        assertTrue(AdminCapability.DECIDE.includes(AdminCapability.VIEW));
        assertFalse(AdminCapability.VIEW.includes(AdminCapability.DECIDE));
        assertEquals(Set.of(RequestAction.APPROVE, RequestAction.DENY, RequestAction.BLOCK),
                RequestActionPolicy.allowed(RequestStatus.PENDING));
        assertEquals(Set.of(), RequestActionPolicy.allowed(RequestStatus.RESOLVING));
        assertEquals(Set.of(RequestAction.UNDO), RequestActionPolicy.allowed(RequestStatus.APPROVED));
        assertEquals(Set.of(RequestAction.REOPEN), RequestActionPolicy.allowed(RequestStatus.DENIED));
        assertEquals(Set.of(RequestAction.REOPEN), RequestActionPolicy.allowed(RequestStatus.BLOCKED));
    }

    @Test
    void requestReferencesRequireCanonicalUuidOrUnambiguousLengthPrefixShape() {
        assertEquals(RequestReference.Kind.UUID, RequestReference.parse("88e56047-7f6f-4c8c-b1f9-123456789abc").kind());
        assertEquals(RequestReference.Kind.UUID_PREFIX, RequestReference.parse("88e56047").kind());
        assertEquals(RequestReference.Kind.UUID_PREFIX, RequestReference.parse("88e56047-7f6f").kind());
        assertEquals("zift1", RequestReference.parse("ZIFT1").normalizedUsername());
        assertThrows(IllegalArgumentException.class, () -> RequestReference.parse("1234567-"));
        assertThrows(IllegalArgumentException.class, () -> RequestReference.parse("88e56047--"));
    }

    private static AdminCommand command(String input) {
        AdminCommandResult result = AdminCommandParser.parse(input);
        assertEquals(AdminCommandResultCode.SUCCESS, result.code(), result.message());
        return (AdminCommand) result.value().orElseThrow();
    }
}
