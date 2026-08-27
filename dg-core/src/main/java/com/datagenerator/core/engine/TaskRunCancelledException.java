package com.datagenerator.core.engine;

/**
 * 任务被用户取消时抛出，区别于一般执行失败。
 */
public class TaskRunCancelledException extends RuntimeException {

    public TaskRunCancelledException() {
        super("Task run cancelled");
    }

    public TaskRunCancelledException(String message) {
        super(message);
    }
}
