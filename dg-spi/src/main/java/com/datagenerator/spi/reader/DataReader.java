package com.datagenerator.spi.reader;

import com.datagenerator.spi.Plugin;
import com.datagenerator.spi.model.DataRow;
import com.datagenerator.spi.model.ReadRequest;
import com.datagenerator.spi.model.ReaderConfig;

import java.util.stream.Stream;

/**
 * Reads rows from an external source.
 */
public interface DataReader extends Plugin {

    void init(ReaderConfig config);

    Stream<DataRow> read(ReadRequest request);

    void close();
}
