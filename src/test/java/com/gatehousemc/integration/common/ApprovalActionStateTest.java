package com.gatehousemc.integration.common;

import com.gatehousemc.domain.DecisionAction;
import com.gatehousemc.domain.RequestStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalActionStateTest {

    @Test
    void pendingOnlyAllowsTerminalDecisions() {
        assertTrue(ApprovalActionState.canExecute(RequestStatus.PENDING, DecisionAction.APPROVE));
        assertTrue(ApprovalActionState.canExecute(RequestStatus.PENDING, DecisionAction.DENY));
        assertTrue(ApprovalActionState.canExecute(RequestStatus.PENDING, DecisionAction.BLOCK));
        assertFalse(ApprovalActionState.canExecute(RequestStatus.PENDING, DecisionAction.UNDO));
    }

    @Test
    void terminalStatesOnlyAllowUndo() {
        for (RequestStatus status : new RequestStatus[]{RequestStatus.APPROVED, RequestStatus.DENIED, RequestStatus.BLOCKED}) {
            assertFalse(ApprovalActionState.canExecute(status, DecisionAction.APPROVE));
            assertFalse(ApprovalActionState.canExecute(status, DecisionAction.DENY));
            assertFalse(ApprovalActionState.canExecute(status, DecisionAction.BLOCK));
            assertTrue(ApprovalActionState.canExecute(status, DecisionAction.UNDO));
        }
    }

    @Test
    void resolvingAllowsNoActions() {
        for (DecisionAction action : DecisionAction.values()) {
            assertFalse(ApprovalActionState.canExecute(RequestStatus.RESOLVING, action));
        }
    }
}
