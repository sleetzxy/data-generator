package com.datagenerator.core.engine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LinkedSeedIndexTest {

    @Test
    void pathMatches_matchesHierarchyPatterns() {
        assertThat(LinkedSeedIndex.PathLinkedSeedMatcher.pathMatches("1,2,3", "2")).isTrue();
        assertThat(LinkedSeedIndex.PathLinkedSeedMatcher.pathMatches("2", "2")).isTrue();
        assertThat(LinkedSeedIndex.PathLinkedSeedMatcher.pathMatches("9,8", "2")).isFalse();
    }

    @Test
    void normalizeKey_unifiesIntegralNumbers() {
        assertThat(LinkedSeedIndex.normalizeKey(10L)).isEqualTo(10L);
        assertThat(LinkedSeedIndex.normalizeKey(10)).isEqualTo(10L);
        assertThat(LinkedSeedIndex.normalizeKey(10.0)).isEqualTo(10L);
    }
}
