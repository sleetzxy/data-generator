package com.datagenerator.core.reference.distribution;

import java.util.List;
import java.util.Map;

/**
 * 基于参考样本值按分布策略采样。
 */
public interface DistributionSampler {

    String distribution();

    Object sample(List<Object> values, Map<String, Object> config);
}
