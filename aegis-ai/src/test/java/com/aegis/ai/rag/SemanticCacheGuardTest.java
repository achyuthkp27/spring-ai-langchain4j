package com.aegis.ai.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticCacheGuardTest {

    private final SemanticCache cache = new SemanticCache(null);

    @Test
    @DisplayName("Context-dependent / short inputs are NOT cacheable")
    void contextDependentNotCacheable() {
        for (String s : new String[]{"yes", "no", "ok", "sure", "hi", "hello", "why", "and", "that one", "go ahead"}) {
            assertThat(cache.isCacheable(s)).as("must not cache: '%s'", s).isFalse();
        }
    }

    @Test
    @DisplayName("Standalone policy questions ARE cacheable")
    void standaloneQuestionsCacheable() {
        assertThat(cache.isCacheable("What is the deadline to file a card dispute?")).isTrue();
        assertThat(cache.isCacheable("How many days do I have to dispute a card charge?")).isTrue();
        assertThat(cache.isCacheable("What are the KYC verification tiers?")).isTrue();
    }
}
