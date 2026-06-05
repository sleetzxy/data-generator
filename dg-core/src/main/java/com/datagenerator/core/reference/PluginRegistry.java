package com.datagenerator.core.reference;

import com.datagenerator.spi.reader.DataReader;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Simple registry mapping reference source names to {@link DataReader} instances.
 * Task 7 will replace this with the full plugin registry.
 */
public class PluginRegistry {

    private final Map<String, DataReader> readers = new HashMap<>();

    public void register(String source, DataReader reader) {
        readers.put(Objects.requireNonNull(source, "source"), Objects.requireNonNull(reader, "reader"));
    }

    public DataReader getReader(String source) {
        DataReader reader = readers.get(source);
        if (reader == null) {
            throw new IllegalArgumentException("Unknown reference source: " + source);
        }
        return reader;
    }
}
