package com.gatehousemc.whitelistrequest.i18n;

import com.gatehousemc.whitelistrequest.domain.DecisionAction;
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
}
