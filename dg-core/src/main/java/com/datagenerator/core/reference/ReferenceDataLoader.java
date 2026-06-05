package com.datagenerator.core.reference;

import java.util.List;
import java.util.Map;

/**
 * Loads reference data for {@link com.datagenerator.core.generator.ReferenceGenerator}.
 * Full implementation in Task 5.
 */
public interface ReferenceDataLoader {

    List<Object> load(String source, Map<String, Object> config);
}
