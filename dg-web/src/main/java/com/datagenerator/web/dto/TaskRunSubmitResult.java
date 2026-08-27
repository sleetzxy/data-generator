package com.datagenerator.web.dto;

/**
 * 任务提交结果，区分同步与异步响应。
 */
public record TaskRunSubmitResult(TaskRunResponse response, boolean async) {
}
