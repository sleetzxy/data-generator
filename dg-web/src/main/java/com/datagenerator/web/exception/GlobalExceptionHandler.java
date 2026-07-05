package com.datagenerator.web.exception;



import com.datagenerator.core.schema.ConfigLoadException;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;

import org.springframework.http.ResponseEntity;

import org.springframework.http.converter.HttpMessageNotReadableException;

import org.springframework.web.bind.annotation.ExceptionHandler;

import org.springframework.web.bind.annotation.RestControllerAdvice;

import org.apache.catalina.connector.ClientAbortException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;



@RestControllerAdvice

public class GlobalExceptionHandler {



    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String FALLBACK_ERROR_MESSAGE = "服务器内部错误，请稍后重试";



    @ExceptionHandler({IllegalArgumentException.class, ConfigLoadException.class})

    public ResponseEntity<ErrorResponse> handleBadRequest(RuntimeException exception) {

        log.warn("Bad request: {}", exception.getMessage());

        return ResponseEntity.badRequest()

                .body(new ErrorResponse(HttpStatus.BAD_REQUEST.value(), resolveMessage(exception)));

    }



    @ExceptionHandler(JobNotFoundException.class)

    public ResponseEntity<ErrorResponse> handleNotFound(JobNotFoundException exception) {

        log.warn("Job not found: {}", exception.getMessage());

        return ResponseEntity.status(HttpStatus.NOT_FOUND)

                .body(new ErrorResponse(HttpStatus.NOT_FOUND.value(), resolveMessage(exception)));

    }



    @ExceptionHandler(AiServiceUnavailableException.class)

    public ResponseEntity<ErrorResponse> handleAiUnavailable(AiServiceUnavailableException exception) {

        log.warn("AI service unavailable: {}", exception.getMessage());

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)

                .body(new ErrorResponse(HttpStatus.SERVICE_UNAVAILABLE.value(), resolveMessage(exception)));

    }



    @ExceptionHandler(ReadOnlyScheduleException.class)

    public ResponseEntity<ErrorResponse> handleReadOnlySchedule(ReadOnlyScheduleException exception) {

        log.warn("Read-only schedule: {}", exception.getMessage());

        return ResponseEntity.status(HttpStatus.FORBIDDEN)

                .body(new ErrorResponse(HttpStatus.FORBIDDEN.value(), resolveMessage(exception)));

    }



    @ExceptionHandler(HttpMessageNotReadableException.class)

    public ResponseEntity<ErrorResponse> handleUnreadableMessage(HttpMessageNotReadableException exception) {

        log.warn("Unreadable request body: {}", exception.getMessage());

        return ResponseEntity.badRequest()

                .body(new ErrorResponse(HttpStatus.BAD_REQUEST.value(), "请求体格式错误，请检查 JSON 内容"));

    }



    @ExceptionHandler(IllegalStateException.class)

    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException exception) {

        log.error("Illegal state", exception);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)

                .body(new ErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), resolveMessage(exception)));

    }



    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsable(AsyncRequestNotUsableException exception) {
        // 客户端主动断开 SSE 连接或请求已超时，属于正常行为
        log.debug("客户端连接已断开: {}", exception.getMessage());
    }

    @ExceptionHandler(ClientAbortException.class)
    public void handleClientAbort(ClientAbortException exception) {
        // 客户端在接收静态资源或 SSE 流时断开连接，属于正常行为
        log.debug("客户端中止连接: {}", exception.getMessage());
    }

    @ExceptionHandler(Exception.class)

    public ResponseEntity<ErrorResponse> handleInternalError(Exception exception) {

        log.error("Unhandled exception", exception);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)

                .body(new ErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), resolveMessage(exception)));

    }



    static String resolveMessage(Throwable exception) {

        String message = exception.getMessage();

        if (message != null && !message.isBlank()) {

            return message.trim();

        }

        Throwable cause = exception.getCause();

        if (cause != null) {

            String causeMessage = cause.getMessage();

            if (causeMessage != null && !causeMessage.isBlank()) {

                return causeMessage.trim();

            }

        }

        return FALLBACK_ERROR_MESSAGE;

    }

}


