package com.datagenerator.core.engine;

import com.datagenerator.core.schema.SeedDefinition;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

public final class SeedDependencySorter {

    private SeedDependencySorter() {
    }

    public static List<SeedDefinition> sort(List<SeedDefinition> seeds) {
        if (seeds.isEmpty()) {
            return List.of();
        }

        Map<String, SeedDefinition> seedsByName = new HashMap<>();
        for (SeedDefinition seed : seeds) {
            if (seed.getName() == null || seed.getName().isBlank()) {
                throw new IllegalArgumentException("seed name is required");
            }
            if (seedsByName.put(seed.getName(), seed) != null) {
                throw new IllegalArgumentException("Duplicate seed name: " + seed.getName());
            }
        }

        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (SeedDefinition seed : seeds) {
            inDegree.putIfAbsent(seed.getName(), 0);
            dependents.putIfAbsent(seed.getName(), new ArrayList<>());
            if (seed.getLink() == null) {
                continue;
            }
            String parentName = seed.getLink().getSeed();
            if (parentName == null || parentName.isBlank()) {
                throw new IllegalArgumentException("seed '" + seed.getName() + "' link.seed is required");
            }
            if (!seedsByName.containsKey(parentName)) {
                throw new IllegalArgumentException("Unknown seed in link: " + parentName);
            }
            dependents.computeIfAbsent(parentName, ignored -> new ArrayList<>()).add(seed.getName());
            inDegree.merge(seed.getName(), 1, Integer::sum);
        }

        Queue<String> ready = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }

        List<SeedDefinition> sorted = new ArrayList<>();
        while (!ready.isEmpty()) {
            String name = ready.remove();
            sorted.add(seedsByName.get(name));
            for (String dependent : dependents.getOrDefault(name, List.of())) {
                int updated = inDegree.merge(dependent, -1, Integer::sum);
                if (updated == 0) {
                    ready.add(dependent);
                }
            }
        }

        if (sorted.size() != seeds.size()) {
            throw new IllegalArgumentException("Cycle detected in seed link graph");
        }
        return sorted;
    }

    /** 收集字段引用的 seed 及其 link 链上的祖先 seed。 */
    public static Set<String> collectRequiredSeedNames(List<SeedDefinition> seeds, Set<String> fieldSources) {
        Map<String, SeedDefinition> seedsByName = new HashMap<>();
        for (SeedDefinition seed : seeds) {
            seedsByName.put(seed.getName(), seed);
        }

        Set<String> required = new HashSet<>();
        for (String source : fieldSources) {
            collectWithAncestors(source, seedsByName, required);
        }
        return required;
    }

    private static void collectWithAncestors(
            String seedName,
            Map<String, SeedDefinition> seedsByName,
            Set<String> required) {
        if (!required.add(seedName)) {
            return;
        }
        SeedDefinition seed = seedsByName.get(seedName);
        if (seed == null) {
            throw new IllegalArgumentException("Unknown seed source: " + seedName);
        }
        if (seed.getLink() != null && seed.getLink().getSeed() != null) {
            collectWithAncestors(seed.getLink().getSeed(), seedsByName, required);
        }
    }
}
