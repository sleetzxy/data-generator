package com.datagenerator.web.internal;

import com.datagenerator.spi.model.Batch;
import com.datagenerator.spi.model.DataRow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CollectingWriterTest {

    @Test
    void toRowMaps_nullFieldValue_preservesNull() {
        DataRow row = new DataRow();
        row.set("id", 1L);
        row.set("optional_note", null);

        CollectingWriter writer = new CollectingWriter();
        writer.write(new Batch("customers", List.of(row)));

        Map<String, List<Map<String, Object>>> rows = writer.toRowMaps();

        assertThat(rows).containsKey("customers");
        assertThat(rows.get("customers")).hasSize(1);
        assertThat(rows.get("customers").getFirst())
                .containsEntry("id", 1L)
                .containsEntry("optional_note", null);
    }
}
