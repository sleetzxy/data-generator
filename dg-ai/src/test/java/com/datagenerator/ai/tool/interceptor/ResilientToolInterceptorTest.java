package com.datagenerator.ai.tool.interceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ResilientToolInterceptorTest {

    private final ResilientToolInterceptor interceptor = new ResilientToolInterceptor();

    @Test
    void intercept_truncatedArguments_returnsFeedback() {
        RuntimeException truncated = new RuntimeException(
                "com.fasterxml.jackson.core.io.JsonEOFException: Unexpected end-of-input");

        String result = interceptor.intercept(
                new ToolExecutionContext("s1", "validateDraftJobYaml", "{}", null, "s1"),
                () -> {
                    throw truncated;
                });

        assertThat(result).isEqualTo(ToolArgumentErrors.TRUNCATED_ARGS_FEEDBACK);
    }

    @Test
    void intercept_otherRuntimeException_rethrows() {
        IllegalStateException failure = new IllegalStateException("GET /jobs 失败: HTTP 500");

        assertThatThrownBy(() -> interceptor.intercept(
                        new ToolExecutionContext("s1", "listJobs", "{}", null, "s1"),
                        () -> {
                            throw failure;
                        }))
                .isSameAs(failure);
    }
}
