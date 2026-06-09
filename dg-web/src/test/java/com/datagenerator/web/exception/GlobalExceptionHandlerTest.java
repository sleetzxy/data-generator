package com.datagenerator.web.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @Test
    void resolveMessage_withMessage_returnsOriginalText() {
        assertThat(GlobalExceptionHandler.resolveMessage(new IllegalStateException("表 customers 缺少 writer 配置")))
                .isEqualTo("表 customers 缺少 writer 配置");
    }

    @Test
    void resolveMessage_withBlankMessage_usesCause() {
        RuntimeException exception = new RuntimeException("", new IllegalArgumentException("YAML 解析失败"));
        assertThat(GlobalExceptionHandler.resolveMessage(exception)).isEqualTo("YAML 解析失败");
    }

    @Test
    void resolveMessage_withoutMessage_returnsFallback() {
        assertThat(GlobalExceptionHandler.resolveMessage(new Exception()))
                .isEqualTo("服务器内部错误，请稍后重试");
    }
}
