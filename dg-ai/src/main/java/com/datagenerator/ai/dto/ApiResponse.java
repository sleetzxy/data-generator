package com.datagenerator.ai.dto;

import java.util.Map;

/** REST 统一响应封装。 */
public class ApiResponse<T> {

    private final String error;
    private final String message;
    private final T data;
    private final Map<String, String> details;

    private ApiResponse(String error, String message, T data, Map<String, String> details) {
        this.error = error;
        this.message = message;
        this.data = data;
        this.details = details;
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(null, null, data, null);
    }

    public static ApiResponse<Void> error(String error, String message) {
        return new ApiResponse<>(error, message, null, null);
    }

    public static ApiResponse<Void> validationError(String message, Map<String, String> details) {
        return new ApiResponse<>("VALIDATION_ERROR", message, null, details);
    }

    public String getError() { return error; }
    public String getMessage() { return message; }
    public T getData() { return data; }
    public Map<String, String> getDetails() { return details; }
}
