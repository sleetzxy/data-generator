package com.datagenerator.core.engine;

/**
 * 任务被用户取消时抛出，区别于一般执行失败。
 */
public class JobCancelledException extends RuntimeException {

    public JobCancelledException() {
        super("Job cancelled");
    }

    public JobCancelledException(String message) {
        super(message);
    }
}
