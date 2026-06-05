package com.datagenerator.core.engine;

import com.datagenerator.core.schema.TableTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

public final class DagSorter {

    private DagSorter() {
    }

    public static List<TableTask> sort(List<TableTask> tables) {
        Map<String, TableTask> tasksByName = new HashMap<>();
        for (TableTask table : tables) {
            if (tasksByName.put(table.getName(), table) != null) {
                throw new IllegalArgumentException("Duplicate table name: " + table.getName());
            }
        }

        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (TableTask table : tables) {
            inDegree.putIfAbsent(table.getName(), 0);
            dependents.putIfAbsent(table.getName(), new ArrayList<>());
            for (String dependency : table.getDependsOn()) {
                if (!tasksByName.containsKey(dependency)) {
                    throw new IllegalArgumentException(
                            "Table '" + table.getName() + "' depends on unknown table: " + dependency);
                }
                dependents.computeIfAbsent(dependency, ignored -> new ArrayList<>()).add(table.getName());
                inDegree.merge(table.getName(), 1, Integer::sum);
            }
        }

        Queue<String> ready = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }

        List<TableTask> sorted = new ArrayList<>();
        while (!ready.isEmpty()) {
            String name = ready.remove();
            sorted.add(tasksByName.get(name));
            for (String dependent : dependents.getOrDefault(name, List.of())) {
                int updated = inDegree.merge(dependent, -1, Integer::sum);
                if (updated == 0) {
                    ready.add(dependent);
                }
            }
        }

        if (sorted.size() != tables.size()) {
            throw new IllegalArgumentException("Cycle detected in table dependency graph");
        }
        return sorted;
    }
}
