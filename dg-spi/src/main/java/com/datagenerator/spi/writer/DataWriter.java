package com.datagenerator.spi.writer;

import com.datagenerator.spi.Plugin;
import com.datagenerator.spi.model.Batch;
import com.datagenerator.spi.model.WriteResult;
import com.datagenerator.spi.model.WriterConfig;

/**
 * Writes generated rows to an external sink.
 */
public interface DataWriter extends Plugin {

    void init(WriterConfig config);

    WriteResult write(Batch batch);

    void flush();

    void close();
}
