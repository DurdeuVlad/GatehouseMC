package com.gatehousemc.i18n;

import com.gatehousemc.domain.DecisionAction;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MessagesTest {

    @Test
    void englishIsLoadedByDefaultAndContainsExpectedKeys() {
        Messages.load("en_us");
        assertEquals("Approve", Messages.get("button.approve"));
        assertEquals("Undo", Messages.get("button.undo"));
        assertTrue(Messages.get("reject.unknown", "Alice").contains("Alice"));
        assertTrue(Messages.get("reject.unknown").contains("{0}"));
    }

    @Test
    void romanianTranslationsLoadCorrectly() {
        Messages.load("ro_ro");
        assertEquals("Aproba", Messages.get("button.approve"));
        assertEquals("Anuleaza", Messages.get("button.undo"));
        assertTrue(Messages.get("reject.unknown", "Bob").contains("Bob"));
    }

    @Test
    void unknownLanguageFallsBackToEnglish() {
        Messages.load("xx_xx");
        assertEquals("Approve", Messages.get("button.approve"));
    }

    @Test
    void missingKeyReturnsKeyItself() {
        Messages.load("en_us");
        assertEquals("nonexistent.key", Messages.get("nonexistent.key"));
    }

    @Test
    void parameterSubstitutionReplacesPlaceholders() {
        Messages.load("en_us");
        String result = Messages.get("reject.pending", "Charlie");
        assertTrue(result.contains("Charlie"));
        assertFalse(result.contains("{0}"));
    }

    @Test
    void decisionMessagesIncludeActorName() {
        Messages.load("en_us");
        assertTrue(Messages.get("decision.approved", "Console").contains("Console"));
        assertTrue(Messages.get("decision.denied", "Admin").contains("Admin"));
        assertTrue(Messages.get("decision.blocked", "Moderator").contains("Moderator"));
        assertTrue(Messages.get("decision.undone", "Operator").contains("Operator"));
    }

    @Test
    void confirmationMessagesExistInBothLanguages() {
        Messages.load("en_us");
        assertNotNull(Messages.get("confirm.approve"));
        assertNotNull(Messages.get("confirm.deny"));
        assertNotNull(Messages.get("confirm.block"));
        assertNotNull(Messages.get("confirm.undo"));
        assertEquals("Confirm", Messages.get("button.confirm"));
        assertEquals("Cancel", Messages.get("button.cancel"));

        Messages.load("ro_ro");
        assertNotNull(Messages.get("confirm.approve"));
        assertEquals("Confirma", Messages.get("button.confirm"));
        assertEquals("Anuleaza", Messages.get("button.cancel"));
    }
}
