package com.datagenerator.core.engine;

import com.datagenerator.spi.model.Batch;
import com.datagenerator.spi.model.WriteResult;
import com.datagenerator.spi.model.WriterConfig;
import com.datagenerator.spi.writer.DataWriter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PluginRegistryTest {

    @Test
    void getWriter_prototypeRegistration_returnsNewInstanceEachCall() {
        PluginRegistry registry = new PluginRegistry();
        registry.registerWriterPrototype("mock", new CountingWriter());

        DataWriter first = registry.getWriter("mock");
        DataWriter second = registry.getWriter("mock");

        assertThat(first).isNotSameAs(second);
    }

    @Test
    void getWriter_sharedRegistration_returnsSameInstance() {
        PluginRegistry registry = new PluginRegistry();
        CountingWriter shared = new CountingWriter();
        registry.registerWriter("mock", shared);

        DataWriter first = registry.getWriter("mock");
        DataWriter second = registry.getWriter("mock");

        assertThat(first).isSameAs(second).isSameAs(shared);
    }

    static final class CountingWriter implements DataWriter {

        @Override
        public String type() {
            return "mock";
        }

        @Override
        public void init(WriterConfig config) {
        }

        @Override
        public WriteResult write(Batch batch) {
            return new WriteResult(0, 0);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
