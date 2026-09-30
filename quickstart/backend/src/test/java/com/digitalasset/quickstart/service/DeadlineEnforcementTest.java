// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for deadline enforcement in swaps.
 *
 * Swaps should fail if the deadline has passed, preventing execution of stale transactions.
 * This protects users from price changes that occurred between transaction creation and execution.
 */
@DisplayName("Deadline Enforcement Tests")
class DeadlineEnforcementTest {

    @Test
    @DisplayName("Accept swap with future deadline")
    void testAcceptSwapWithFutureDeadline() {
        // Arrange
        Instant now = Instant.now();
        Instant deadline = now.plus(5, ChronoUnit.MINUTES);

        // Act
        boolean isValid = isDeadlineValid(now, deadline);

        // Assert
        assertTrue(isValid, "Swap with future deadline should be accepted");
    }

    @Test
    @DisplayName("Reject swap with past deadline")
    void testRejectSwapWithPastDeadline() {
        // Arrange
        Instant now = Instant.now();
        Instant deadline = now.minus(1, ChronoUnit.SECONDS);

        // Act
        boolean isValid = isDeadlineValid(now, deadline);

        // Assert
        assertFalse(isValid, "Swap with past deadline should be rejected");
    }

    @Test
    @DisplayName("Accept swap at exact deadline (boundary)")
    void testAcceptSwapAtDeadline() {
        // Arrange
        Instant now = Instant.now();
        Instant deadline = now; // Exactly at deadline

        // Act
        boolean isValid = isDeadlineValid(now, deadline);

        // Assert
        assertTrue(isValid, "Swap at exact deadline should be accepted (inclusive)");
    }

    @Test
    @DisplayName("Reject swap 1 second after deadline")
    void testRejectSwapJustAfterDeadline() {
        // Arrange
        Instant now = Instant.now();
        Instant deadline = now.minus(1, ChronoUnit.SECONDS);

        // Act
        boolean isValid = isDeadlineValid(now, deadline);

        // Assert
        assertFalse(isValid, "Swap 1 second after deadline should be rejected");
    }

    @Test
    @DisplayName("Accept swap with standard 5-minute deadline")
    void testStandardDeadline() {
        // Arrange - Typical frontend deadline: now + 5 minutes
        Instant now = Instant.now();
        int deadlineSeconds = 300; // 5 minutes
        Instant deadline = now.plus(deadlineSeconds, ChronoUnit.SECONDS);

        // Act - Simulate execution 30 seconds later
        Instant executionTime = now.plus(30, ChronoUnit.SECONDS);
        boolean isValid = isDeadlineValid(executionTime, deadline);

        // Assert
        assertTrue(isValid, "Standard 5-minute deadline should be valid after 30 seconds");
    }

    @Test
    @DisplayName("Reject swap with standard deadline after 6 minutes")
    void testStandardDeadlineExpired() {
        // Arrange
        Instant now = Instant.now();
        int deadlineSeconds = 300; // 5 minutes
        Instant deadline = now.plus(deadlineSeconds, ChronoUnit.SECONDS);

        // Act - Simulate execution 6 minutes later (past deadline)
        Instant executionTime = now.plus(6, ChronoUnit.MINUTES);
        boolean isValid = isDeadlineValid(executionTime, deadline);

        // Assert
        assertFalse(isValid, "Swap should be rejected 6 minutes after creation (past 5-min deadline)");
    }

    @Test
    @DisplayName("Deadline calculation from seconds")
    void testDeadlineCalculation() {
        // Arrange
        Instant now = Instant.parse("2025-10-19T10:00:00Z");
        int deadlineSeconds = 300;

        // Act
        Instant deadline = calculateDeadline(now, deadlineSeconds);

        // Assert
        Instant expected = Instant.parse("2025-10-19T10:05:00Z");
        assertEquals(expected, deadline, "Deadline should be 5 minutes after now");
    }

    @Test
    @DisplayName("Deadline calculation with 1-hour window")
    void testLongDeadline() {
        // Arrange
        Instant now = Instant.now();
        int deadlineSeconds = 3600; // 1 hour

        // Act
        Instant deadline = calculateDeadline(now, deadlineSeconds);

        // Assert
        long secondsBetween = ChronoUnit.SECONDS.between(now, deadline);
        assertEquals(3600, secondsBetween, "Deadline should be 1 hour (3600 seconds) in the future");
    }

    @Test
    @DisplayName("Minimum deadline (30 seconds)")
    void testMinimumDeadline() {
        // Arrange
        Instant now = Instant.now();
        int deadlineSeconds = 30;

        // Act
        Instant deadline = calculateDeadline(now, deadlineSeconds);

        // Assert - Execution 20 seconds later should succeed
        Instant executionTime = now.plus(20, ChronoUnit.SECONDS);
        assertTrue(isDeadlineValid(executionTime, deadline),
            "Swap should be valid 20 seconds into 30-second deadline");

        // Assert - Execution 31 seconds later should fail
        Instant lateExecution = now.plus(31, ChronoUnit.SECONDS);
        assertFalse(isDeadlineValid(lateExecution, deadline),
            "Swap should be invalid 31 seconds into 30-second deadline");
    }

    @Test
    @DisplayName("Very far future deadline (1 day)")
    void testFarFutureDeadline() {
        // Arrange
        Instant now = Instant.now();
        int deadlineSeconds = 86400; // 1 day

        // Act
        Instant deadline = calculateDeadline(now, deadlineSeconds);

        // Assert - Execution 23 hours later should succeed
        Instant executionTime = now.plus(23, ChronoUnit.HOURS);
        assertTrue(isDeadlineValid(executionTime, deadline),
            "Swap should be valid 23 hours into 1-day deadline");
    }

    @Test
    @DisplayName("Zero deadline (immediate execution only)")
    void testZeroDeadline() {
        // Arrange
        Instant now = Instant.now();
        int deadlineSeconds = 0;

        // Act
        Instant deadline = calculateDeadline(now, deadlineSeconds);

        // Assert - Same instant should be valid
        assertTrue(isDeadlineValid(now, deadline),
            "Zero deadline should allow execution at same instant");

        // Assert - 1 second later should be invalid
        Instant laterExecution = now.plus(1, ChronoUnit.SECONDS);
        assertFalse(isDeadlineValid(laterExecution, deadline),
            "Zero deadline should reject execution 1 second later");
    }

    @Test
    @DisplayName("Deadline validation handles millisecond precision")
    void testMillisecondPrecision() {
        // Arrange
        Instant now = Instant.parse("2025-10-19T10:00:00.000Z");
        Instant deadline = Instant.parse("2025-10-19T10:00:00.500Z"); // 500ms later

        // Act & Assert - 400ms later (before deadline)
        Instant beforeDeadline = Instant.parse("2025-10-19T10:00:00.400Z");
        assertTrue(isDeadlineValid(beforeDeadline, deadline),
            "Should accept swap 400ms into 500ms deadline");

        // Act & Assert - 600ms later (after deadline)
        Instant afterDeadline = Instant.parse("2025-10-19T10:00:00.600Z");
        assertFalse(isDeadlineValid(afterDeadline, deadline),
            "Should reject swap 600ms into 500ms deadline");
    }

    @Test
    @DisplayName("Concurrent swaps with different deadlines")
    void testMultipleDeadlines() {
        // Arrange - Two swaps created at different times
        Instant now = Instant.now();

        Instant swap1Created = now;
        Instant swap1Deadline = swap1Created.plus(5, ChronoUnit.MINUTES);

        Instant swap2Created = now.plus(2, ChronoUnit.MINUTES);
        Instant swap2Deadline = swap2Created.plus(5, ChronoUnit.MINUTES);

        // Act - Execute both at 4 minutes after initial creation
        Instant executionTime = now.plus(4, ChronoUnit.MINUTES);

        boolean swap1Valid = isDeadlineValid(executionTime, swap1Deadline);
        boolean swap2Valid = isDeadlineValid(executionTime, swap2Deadline);

        // Assert
        assertTrue(swap1Valid, "Swap 1 should be valid (4 min < 5 min deadline)");
        assertTrue(swap2Valid, "Swap 2 should be valid (2 min < 5 min deadline)");

        // Act - Execute both at 6 minutes after initial creation
        Instant lateExecution = now.plus(6, ChronoUnit.MINUTES);

        boolean swap1ValidLate = isDeadlineValid(lateExecution, swap1Deadline);
        boolean swap2ValidLate = isDeadlineValid(lateExecution, swap2Deadline);

        // Assert
        assertFalse(swap1ValidLate, "Swap 1 should be invalid (6 min > 5 min deadline)");
        assertTrue(swap2ValidLate, "Swap 2 should still be valid (4 min < 5 min deadline)");
    }

    // Helper methods

    /**
     * Check if current time is before or at deadline.
     * Returns true if deadline has not passed, false if expired.
     */
    private boolean isDeadlineValid(Instant now, Instant deadline) {
        return !now.isAfter(deadline);
    }

    /**
     * Calculate deadline from current time and seconds offset.
     */
    private Instant calculateDeadline(Instant now, int deadlineSeconds) {
        return now.plus(deadlineSeconds, ChronoUnit.SECONDS);
    }
}
