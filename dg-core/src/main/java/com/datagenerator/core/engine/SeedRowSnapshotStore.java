package com.datagenerator.core.engine;

import com.datagenerator.spi.model.DataRow;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Job 级 L2 行级 seed 快照：按输出行号缓存各 seed 采样结果，供下游表按 index 复用，避免重复随机抽样。
 */
public class SeedRowSnapshotStore {

    private final ConcurrentHashMap<Integer, Map<String, DataRow>> rows = new ConcurrentHashMap<>();

    /**
     * 读取指定行的 seed 快照（只读视图，可能为 null）。
     */
    public Map<String, DataRow> getRow(int rowIndex) {
        Map<String, DataRow> snapshot = rows.get(rowIndex);
        if (snapshot == null || snapshot.isEmpty()) {
            return null;
        }
        return snapshot;
    }

    /**
     * 将本次采样结果合并进指定行快照。
     */
    public void mergeRow(int rowIndex, Map<String, DataRow> samples) {
        if (rowIndex < 0 || samples == null || samples.isEmpty()) {
            return;
        }
        rows.computeIfAbsent(rowIndex, ignored -> new HashMap<>()).putAll(samples);
    }

    /**
     * 判断指定行是否已缓存全部所需 seed。
     */
    public boolean containsAll(int rowIndex, Set<String> requiredSeedNames) {
        if (requiredSeedNames.isEmpty()) {
            return true;
        }
        Map<String, DataRow> snapshot = rows.get(rowIndex);
        if (snapshot == null) {
            return false;
        }
        return snapshot.keySet().containsAll(requiredSeedNames);
    }

    /**
     * 按所需 seed 名从行快照中取出子集。
     */
    public Map<String, DataRow> pick(int rowIndex, Set<String> requiredSeedNames) {
        Map<String, DataRow> snapshot = rows.get(rowIndex);
        Map<String, DataRow> picked = new HashMap<>();
        for (String seedName : requiredSeedNames) {
            picked.put(seedName, snapshot.get(seedName));
        }
        return picked;
    }

    int rowCount() {
        return rows.size();
    }
}
