package com.datagenerator.core.engine;

import com.datagenerator.spi.inspector.ColumnMeta;
import com.datagenerator.spi.inspector.ConnectionInspector;
import com.datagenerator.spi.inspector.ConnectionProbe;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginRegistryInspectorTest {

    @Test
    void getInspector_registeredType_returnsSameInstance() {
        PluginRegistry registry = new PluginRegistry();
        StubInspector inspector = new StubInspector("postgresql");
        registry.registerInspector("postgresql", inspector);

        ConnectionInspector found = registry.getInspector("postgresql");

        assertThat(found).isSameAs(inspector);
    }

    @Test
    void getInspector_unknownType_throwsIllegalArgumentException() {
        PluginRegistry registry = new PluginRegistry();

        assertThatThrownBy(() -> registry.getInspector("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inspector")
                .hasMessageContaining("unknown");
    }

    static final class StubInspector implements ConnectionInspector {

        private final String type;

        StubInspector(String type) {
            this.type = type;
        }

        @Override
        public String type() {
            return type;
        }

        @Override
        public void test(ConnectionProbe probe) {
        }

        @Override
        public List<String> listTables(
                ConnectionProbe probe, String schemaOrNull, String patternOrNull) {
            return List.of();
        }

        @Override
        public List<ColumnMeta> listColumns(ConnectionProbe probe, String table) {
            return List.of();
        }
    }
}
