package com.datagenerator.ai.web;



import com.datagenerator.ai.exception.AiDisabledException;
import com.datagenerator.ai.exception.SessionConflictException;
import com.datagenerator.ai.exception.SessionNotFoundException;

import com.datagenerator.ai.web.dto.common.ApiResponse;

import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;

import org.springframework.http.MediaType;

import org.springframework.http.ResponseEntity;

import org.springframework.validation.FieldError;

import org.springframework.web.bind.MethodArgumentNotValidException;

import org.springframework.web.bind.annotation.ExceptionHandler;

import org.springframework.web.bind.annotation.RestControllerAdvice;

import org.springframework.web.context.request.async.AsyncRequestTimeoutException;



@RestControllerAdvice

public class AiExceptionHandler {



    private static final Logger log = LoggerFactory.getLogger(AiExceptionHandler.class);



    @ExceptionHandler(MethodArgumentNotValidException.class)

    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {

        var fields = ex.getBindingResult().getFieldErrors()

                .stream()

                .collect(Collectors.toMap(FieldError::getField, FieldError::getDefaultMessage, (a, b) -> a));

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)

                .body(ApiResponse.validationError("请求参数校验失败", fields));

    }



    @ExceptionHandler(IllegalArgumentException.class)

    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)

                .body(ApiResponse.error(

                        "INVALID_ARGUMENT",

                        ex.getMessage() != null ? ex.getMessage() : "参数错误"));

    }



    @ExceptionHandler(SessionConflictException.class)

    public ResponseEntity<ApiResponse<Void>> handleSessionConflict(SessionConflictException ex) {

        return ResponseEntity.status(HttpStatus.CONFLICT)

                .body(ApiResponse.error(

                        "SESSION_CONFLICT",

                        ex.getMessage() != null ? ex.getMessage() : "会话已有进行中的对话"));

    }



    @ExceptionHandler(SessionNotFoundException.class)

    public ResponseEntity<ApiResponse<Void>> handleNotFound(SessionNotFoundException ex) {

        return ResponseEntity.status(HttpStatus.NOT_FOUND)

                .body(ApiResponse.error(

                        "SESSION_NOT_FOUND",

                        ex.getMessage() != null ? ex.getMessage() : "会话未找到"));

    }



    @ExceptionHandler(AiDisabledException.class)

    public ResponseEntity<ApiResponse<Void>> handleAiDisabled(AiDisabledException ex) {

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)

                .body(ApiResponse.error(

                        "AI_DISABLED",

                        ex.getMessage() != null ? ex.getMessage() : "AI 未启用"));

    }



    @ExceptionHandler(AsyncRequestTimeoutException.class)

    public ResponseEntity<Void> handleAsyncTimeout(AsyncRequestTimeoutException ex, HttpServletRequest request) {

        log.warn("Async request timed out: {} {}", request.getMethod(), request.getRequestURI());

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();

    }



    @ExceptionHandler(Exception.class)

    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex, HttpServletRequest request) {

        if (ex instanceof AsyncRequestTimeoutException) {

            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();

        }

        String accept = request.getHeader("Accept");

        if (accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {

            log.error("Unhandled exception on SSE endpoint (response may be committed): {}", ex.getMessage());

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();

        }

        log.error("Unhandled exception in AI module", ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)

                .body(ApiResponse.error(

                        "INTERNAL_ERROR",

                        ex.getMessage() != null ? ex.getMessage() : "内部服务器错误"));

    }

}

