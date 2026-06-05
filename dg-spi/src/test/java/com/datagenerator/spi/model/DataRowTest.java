package com.datagenerator.spi.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DataRowTest {

    @Test
    void getAndSetField() {
        DataRow row = new DataRow();
        row.set("name", "Alice");
        assertEquals("Alice", row.get("name"));
    }
}
