package com.datagenerator.core.engine;

/**
 * 任务执行过程中的取消探测，由 Web 层注入。
 */
@FunctionalInterface
public interface CancellationChecker {

    CancellationChecker NEVER = () -> false;

    boolean isCancelled();
}
