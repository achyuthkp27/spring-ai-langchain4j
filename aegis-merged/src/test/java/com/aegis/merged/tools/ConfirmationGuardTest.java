package com.aegis.merged.tools;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConfirmationGuardTest {

    private final ConfirmationGuard guard = new ConfirmationGuard();

    @Test
    void aFreshTokenVerifiesForTheExactSameCall() {
        String token = guard.issue("u1", "freezeCard", "CRD-7001");
        assertThat(guard.verify(token, "u1", "freezeCard", "CRD-7001")).isTrue();
    }

    @Test
    void aTokenCanOnlyBeUsedOnce() {
        String token = guard.issue("u1", "freezeCard", "CRD-7001");
        assertThat(guard.verify(token, "u1", "freezeCard", "CRD-7001")).isTrue();
        assertThat(guard.verify(token, "u1", "freezeCard", "CRD-7001")).isFalse();
    }

    @Test
    void aTokenDoesNotConfirmDifferentArguments() {
        String token = guard.issue("u1", "freezeCard", "CRD-7001");
        assertThat(guard.verify(token, "u1", "freezeCard", "CRD-7002")).isFalse();
    }

    @Test
    void aMismatchedVerifyAttemptBurnsTheTokenLikeTheRedisPathDoes() {
        String token = guard.issue("u1", "freezeCard", "CRD-7001");
        assertThat(guard.verify(token, "u1", "freezeCard", "CRD-7002")).isFalse();

        assertThat(guard.verify(token, "u1", "freezeCard", "CRD-7001")).isFalse();
    }

    @Test
    void aTokenDoesNotConfirmADifferentTool() {
        String token = guard.issue("u1", "freezeCard", "CRD-7001");
        assertThat(guard.verify(token, "u1", "unfreezeCard", "CRD-7001")).isFalse();
    }

    @Test
    void aTokenDoesNotConfirmForADifferentUser() {
        String token = guard.issue("u1", "freezeCard", "CRD-7001");
        assertThat(guard.verify(token, "u2", "freezeCard", "CRD-7001")).isFalse();
    }

    @Test
    void aFabricatedTokenNeverVerifies() {
        assertThat(guard.verify("not-a-real-token", "u1", "freezeCard", "CRD-7001")).isFalse();
    }

    @Test
    void aNullOrBlankTokenNeverVerifies() {
        assertThat(guard.verify(null, "u1", "freezeCard", "CRD-7001")).isFalse();
        assertThat(guard.verify("", "u1", "freezeCard", "CRD-7001")).isFalse();
    }

    @Test
    void differentToolsForTheSameArgumentsMintDifferentTokens() {
        String freeze = guard.issue("u1", "freezeCard", "CRD-7001");
        String replace = guard.issue("u1", "requestCardReplacement", "CRD-7001");
        assertThat(freeze).isNotEqualTo(replace);
        assertThat(guard.verify(freeze, "u1", "requestCardReplacement", "CRD-7001")).isFalse();
        assertThat(guard.verify(replace, "u1", "requestCardReplacement", "CRD-7001")).isTrue();
    }
}
