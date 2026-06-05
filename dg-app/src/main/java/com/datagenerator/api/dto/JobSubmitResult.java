package com.datagenerator.api.dto;

/**
 * 任务提交结果，区分同步与异步响应。
 */
public record JobSubmitResult(JobResponse response, boolean async) {
}
