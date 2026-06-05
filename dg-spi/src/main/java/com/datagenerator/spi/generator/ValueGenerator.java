package com.datagenerator.spi.generator;

import com.datagenerator.spi.Plugin;
import com.datagenerator.spi.model.GenerationContext;

import java.util.Map;

/**
 * Generates a single field value for a row.
 */
public interface ValueGenerator extends Plugin {

    String strategy();

    Object generate(GenerationContext ctx, Map<String, Object> config);
}
