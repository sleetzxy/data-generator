package com.datagenerator.core.engine;

import com.datagenerator.spi.model.DataRow;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SeedRowSnapshotStoreTest {

    @Test
    void containsAll_andPick_returnCachedSubset() {
        SeedRowSnapshotStore store = new SeedRowSnapshotStore();
        DataRow road = new DataRow(Map.of("roadclid", "R1", "jd", "113.1"));
        DataRow police = new DataRow(Map.of("fxjg", "P1"));
        store.mergeRow(0, Map.of("road_sample", road, "police_station_sample", police));

        assertThat(store.containsAll(0, Set.of("road_sample"))).isTrue();
        assertThat(store.containsAll(0, Set.of("road_sample", "police_station_sample"))).isTrue();
        assertThat(store.containsAll(0, Set.of("road_sample", "wf_dict_sample"))).isFalse();

        Map<String, DataRow> picked = store.pick(0, Set.of("road_sample"));
        assertThat(picked.get("road_sample").get("roadclid")).isEqualTo("R1");
    }

    @Test
    void mergeRow_mergesIntoSameRowIndex() {
        SeedRowSnapshotStore store = new SeedRowSnapshotStore();
        store.mergeRow(1, Map.of("road_sample", new DataRow(Map.of("jd", "1.0"))));
        store.mergeRow(1, Map.of("wf_dict_sample", new DataRow(Map.of("wfxw", "001"))));

        assertThat(store.rowCount()).isEqualTo(1);
        assertThat(store.containsAll(1, Set.of("road_sample", "wf_dict_sample"))).isTrue();
    }
}
