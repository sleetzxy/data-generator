package com.datagenerator.core.engine;

import com.datagenerator.core.schema.TableTask;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DagSorterTest {

    @Test
    void dagSorter_topologicalOrder() {
        List<TableTask> tables = List.of(
                task("orders", List.of("customers")),
                task("customers", List.of()),
                task("order_items", List.of("orders")));

        assertThat(DagSorter.sort(tables).stream().map(TableTask::getName))
                .containsExactly("customers", "orders", "order_items");
    }

    @Test
    void dagSorter_detectsCycle() {
        assertThatThrownBy(() -> DagSorter.sort(List.of(
                task("a", List.of("b")),
                task("b", List.of("a")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cycle");
    }

    @Test
    void dagSorter_preservesInputWhenNoDependencies() {
        List<TableTask> tables = new ArrayList<>(List.of(
                task("alpha", List.of()),
                task("beta", List.of())));
        assertThat(DagSorter.sort(tables).stream().map(TableTask::getName))
                .containsExactly("alpha", "beta");
    }

    private static TableTask task(String name, List<String> dependsOn) {
        TableTask task = new TableTask();
        task.setName(name);
        task.setDependsOn(dependsOn);
        return task;
    }
}
