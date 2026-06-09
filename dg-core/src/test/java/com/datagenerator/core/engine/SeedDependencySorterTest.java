package com.datagenerator.core.engine;

import com.datagenerator.core.schema.SeedDefinition;
import com.datagenerator.core.schema.SeedLinkDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeedDependencySorterTest {

    @Test
    void sort_rootAndLinkedSeeds_returnsTopologicalOrder() {
        SeedDefinition header = seed("header", null);
        SeedDefinition detail = linkedSeed("detail", "header");

        List<SeedDefinition> sorted = SeedDependencySorter.sort(List.of(detail, header));

        assertThat(sorted.stream().map(SeedDefinition::getName)).containsExactly("header", "detail");
    }

    @Test
    void sort_cycleDetected_throws() {
        SeedDefinition a = linkedSeed("a", "b");
        SeedDefinition b = linkedSeed("b", "a");

        assertThatThrownBy(() -> SeedDependencySorter.sort(List.of(a, b)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cycle detected in seed link graph");
    }

    @Test
    void sort_duplicateName_throws() {
        SeedDefinition first = seed("dup", null);
        SeedDefinition second = seed("dup", null);

        assertThatThrownBy(() -> SeedDependencySorter.sort(List.of(first, second)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate seed name");
    }

    private static SeedDefinition seed(String name, SeedLinkDefinition link) {
        SeedDefinition seed = new SeedDefinition();
        seed.setName(name);
        seed.setLink(link);
        seed.setTemplate(Map.of("id", 1));
        return seed;
    }

    private static SeedDefinition linkedSeed(String name, String parent) {
        SeedLinkDefinition link = new SeedLinkDefinition();
        link.setSeed(parent);
        link.setOn("id");
        return seed(name, link);
    }
}
