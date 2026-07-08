package com.datagenerator.ai.exception;

import com.datagenerator.ai.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

/**
 * dg-ai 全局异常处理。
 *
 * <p>SSE 流式响应场景特殊处理：一旦响应头已提交（Content-Type 锁为 text/event-stream），
 * 无法再返回 JSON 格式的 ApiResponse，只能打日志并返回空 body。
 */
@RestControllerAdvice
public class AiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AiExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        var fields = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(FieldError::getField, FieldError::getDefaultMessage, (a, b) -> a));
        return ResponseEntity.badRequest().body(ApiResponse.validationError("参数校验失败", fields));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest req) {
        return respond(req, HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", msg(ex));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> handleIllegalState(IllegalStateException ex, HttpServletRequest req) {
        // Tomcat AsyncContext 竞态：SSE 流异常终止后非容器线程尝试操作 AsyncContext。
        // 此时响应早已提交，只需打日志并返回空 body，不尝试写 JSON。
        if (ex.getMessage() != null && ex.getMessage().contains("AsyncContext")) {
            log.debug("SSE AsyncContext 已关闭: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        return respond(req, HttpStatus.BAD_REQUEST, "INVALID_STATE", msg(ex));
    }

    /**
     * SSE 流进行中客户端断开 —— 客户端已不在，无需尝试写响应。
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public ResponseEntity<Void> handleAsyncNotUsable(AsyncRequestNotUsableException e) {
        log.debug("SSE 客户端已断开: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<Void> handleAsyncTimeout() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    /**
     * SSE 响应已提交后尝试写 JSON 导致的消息转换异常 —— 响应头已锁，回退为空 body。
     */
    @ExceptionHandler(HttpMessageNotWritableException.class)
    public ResponseEntity<Void> handleNotWritable(HttpMessageNotWritableException e, HttpServletResponse resp) {
        if (resp.isCommitted()) {
            // SSE 流已提交：无法返回 JSON，但应记录原始错误原因（可能在 cause 链中）
            Throwable root = e.getCause() != null ? e.getCause() : e;
            log.warn("SSE 响应已提交，原始异常: {}", root.getMessage());
        } else {
            log.warn("响应写入失败: {}", e.getMessage());
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleAgentException(Exception e, HttpServletRequest req) {
        log.error("AI 处理异常", e);
        return respond(req, HttpStatus.INTERNAL_SERVER_ERROR,
                "ERROR", msg(e));
    }

    /**
     * SSE 请求返回空 body（响应头已锁为 text/event-stream 时无法写 JSON），
     * 普通请求返回 ApiResponse JSON。
     */
    private static ResponseEntity<?> respond(HttpServletRequest req, HttpStatus status, String code, String msg) {
        if (isSse(req)) return ResponseEntity.status(status).build();
        return ResponseEntity.status(status).body(ApiResponse.error(code, msg));
    }

    private static boolean isSse(HttpServletRequest req) {
        String accept = req.getHeader("Accept");
        return accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE);
    }

    private static String msg(Exception ex) {
        return ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
    }
}
