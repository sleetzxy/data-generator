package com.datagenerator.ai.port.http;

import com.datagenerator.ai.util.TextUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpClient.Version;
import java.time.Duration;
import java.util.Arrays;

/**
 * 通用 HTTP 客户端，用于 Tool 回调 dg-web 现有 REST API。
 */
public class HttpServiceClient {

    public static final String SERVICE_AUTH_HEADER = "X-DG-Service-Auth";
    private static final int MAX_ATTEMPTS = 2;

    private final String baseUrl;
    private final String serviceAuthToken;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration timeout;

    public HttpServiceClient(
            String baseUrl,
            String serviceAuthToken,
            Duration timeout,
            ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.timeout = timeout;
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.serviceAuthToken = serviceAuthToken;
        this.httpClient = HttpClient.newBuilder()
                .version(Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();
    }

    public <T> T get(String path, TypeReference<T> typeReference) {
        return exchange("GET", path, null, typeReference, 200);
    }

    public <T> T post(String path, Object body, TypeReference<T> typeReference) {
        return exchange("POST", path, body, typeReference, 200);
    }

    public <T> T postCreated(String path, Object body, TypeReference<T> typeReference) {
        return exchange("POST", path, body, typeReference, 201);
    }

    public <T> T postAccept(String path, Object body, TypeReference<T> typeReference) {
        return exchange("POST", path, body, typeReference, 200, 202);
    }

    public <T> T put(String path, Object body, TypeReference<T> typeReference) {
        return exchange("PUT", path, body, typeReference, 200);
    }

    public void delete(String path) {
        HttpRequest.Builder builder = applyAuth(HttpRequest.newBuilder()
                .uri(uri(path))
                .timeout(timeout)
                .DELETE());
        HttpResponse<String> response = sendWithRetry("DELETE", path, builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 204) {
            throw new IllegalStateException("DELETE " + path + " 失败: HTTP " + response.statusCode());
        }
    }

    private <T> T exchange(
            String method,
            String path,
            Object body,
            TypeReference<T> typeReference,
            int... expectedStatuses) {
        try {
            HttpRequest.Builder builder = applyAuth(HttpRequest.newBuilder()
                    .uri(uri(path))
                    .timeout(timeout));
            attachBody(builder, method, body);
            HttpResponse<String> response =
                    sendWithRetry(method, path, builder.build(), HttpResponse.BodyHandlers.ofString());
            if (!isExpectedStatus(response.statusCode(), expectedStatuses)) {
                String hint = response.statusCode() == 401
                        ? "（请检查 dg-web data-generator.service-auth.token 与 dg-ai service-auth-token 是否一致）"
                        : "";
                throw new IllegalStateException(
                        method + " " + path + " 失败: HTTP " + response.statusCode() + hint + " " + response.body());
            }
            if (response.body() == null || response.body().isBlank()) {
                return null;
            }
            return objectMapper.readValue(response.body(), typeReference);
        } catch (IOException exception) {
            throw new IllegalStateException(method + " " + path + " 失败", exception);
        }
    }

    private <T> HttpResponse<T> sendWithRetry(
            String method,
            String path,
            HttpRequest request,
            HttpResponse.BodyHandler<T> bodyHandler) {
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return httpClient.send(request, bodyHandler);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(method + " " + path + " 失败", exception);
            } catch (IOException exception) {
                lastFailure = exception;
                if (attempt >= MAX_ATTEMPTS || !isRetryable(exception)) {
                    break;
                }
            }
        }
        throw new IllegalStateException(method + " " + path + " 失败: " + lastFailure.getMessage(), lastFailure);
    }

    private static boolean isRetryable(IOException exception) {
        String message = exception.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("connection reset")
                || lower.contains("connection refused")
                || lower.contains("broken pipe");
    }

    private void attachBody(HttpRequest.Builder builder, String method, Object body) throws IOException {
        if ("GET".equals(method) || "DELETE".equals(method)) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
            return;
        }
        String json = body == null ? "{}" : objectMapper.writeValueAsString(body);
        builder.header("Content-Type", "application/json");
        builder.method(method, HttpRequest.BodyPublishers.ofString(json));
    }

    private boolean isExpectedStatus(int actual, int... expectedStatuses) {
        return Arrays.stream(expectedStatuses).anyMatch(expected -> expected == actual);
    }

    private HttpRequest.Builder applyAuth(HttpRequest.Builder builder) {
        if (TextUtils.hasText(serviceAuthToken)) {
            builder.header(SERVICE_AUTH_HEADER, serviceAuthToken);
        }
        return builder;
    }

    private URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    private static String normalizeBaseUrl(String url) {
        if (!TextUtils.hasText(url)) {
            throw new IllegalStateException("Tool 服务 base-url 未配置");
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
