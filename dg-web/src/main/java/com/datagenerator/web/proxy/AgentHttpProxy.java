package com.datagenerator.web.proxy;

import com.datagenerator.web.config.DataGeneratorProperties;
import com.datagenerator.web.exception.AiServiceUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpClient.Version;
import java.time.Duration;
import java.util.Enumeration;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 将 /api/v1/agent 请求透明转发至独立 dg-ai 服务，不做 JSON 解析或 DTO 映射。
 */
@Component
@ConditionalOnProperty(prefix = "data-generator.ai", name = "enabled", havingValue = "true")
public class AgentHttpProxy {

    private static final Logger log = LoggerFactory.getLogger(AgentHttpProxy.class);
    private static final int STREAM_BUFFER_SIZE = 512;
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailers", "transfer-encoding", "upgrade", "host", "content-length");

    private final DataGeneratorProperties.AiProperties aiProperties;
    private final HttpClient httpClient;

    public AgentHttpProxy(DataGeneratorProperties properties) {
        this.aiProperties = properties.getAi();
        this.httpClient = HttpClient.newBuilder()
                .version(Version.HTTP_1_1)
                .connectTimeout(aiProperties.getRequestTimeout())
                .build();
    }

    public void forward(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String targetUrl = resolveTargetUrl(request);
        boolean sseStream = isSseStream(request);
        HttpRequest upstreamRequest = buildUpstreamRequest(request, targetUrl);
        try {
            HttpResponse<InputStream> upstreamResponse =
                    httpClient.send(upstreamRequest, HttpResponse.BodyHandlers.ofInputStream());
            copyResponseHeaders(upstreamResponse, response);
            if (sseStream) {
                applySseResponseHeaders(response);
            }
            response.setStatus(upstreamResponse.statusCode());
            try (InputStream input = upstreamResponse.body()) {
                streamToClient(input, response, sseStream);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("转发 Agent 请求被中断", exception);
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("转发 Agent 请求失败", exception);
        }
    }

    private void streamToClient(InputStream input, HttpServletResponse response, boolean sseStream)
            throws IOException {
        OutputStream output = response.getOutputStream();
        byte[] buffer = new byte[STREAM_BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
            if (sseStream) {
                output.flush();
            }
        }
        output.flush();
        response.flushBuffer();
    }

    private void applySseResponseHeaders(HttpServletResponse response) {
        if (response.getContentType() == null) {
            response.setContentType("text/event-stream;charset=UTF-8");
        }
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");
    }

    private boolean isSseStream(HttpServletRequest request) {
        if (request.getRequestURI() != null && request.getRequestURI().endsWith("/messages")) {
            return true;
        }
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("text/event-stream");
    }

    private String resolveTargetUrl(HttpServletRequest request) {
        String baseUrl = aiProperties.getRemoteBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new AiServiceUnavailableException("未配置 data-generator.ai.remote-base-url");
        }
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String uri = request.getRequestURI();
        String query = request.getQueryString();
        String target = baseUrl + uri;
        if (query != null && !query.isBlank()) {
            target = target + "?" + query;
        }
        log.debug("Agent proxy: {} {} -> {}", request.getMethod(), uri, target);
        return target;
    }

    private HttpRequest buildUpstreamRequest(HttpServletRequest request, String targetUrl) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl));
        Duration timeout = resolveTimeout(request);
        if (!timeout.isZero()) {
            builder.timeout(timeout);
        }
        copyRequestHeaders(request, builder);
        builder.method(request.getMethod(), resolveBodyPublisher(request));
        return builder.build();
    }

    /** SSE 长连接不设请求超时，避免 Tool 执行期间被 HttpClient 强制断开。 */
    private Duration resolveTimeout(HttpServletRequest request) {
        if (isSseStream(request)) {
            return Duration.ZERO;
        }
        return aiProperties.getRequestTimeout();
    }

    private HttpRequest.BodyPublisher resolveBodyPublisher(HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readAllBytes();
        if (body.length == 0) {
            return HttpRequest.BodyPublishers.noBody();
        }
        return HttpRequest.BodyPublishers.ofByteArray(body);
    }

    private void copyRequestHeaders(HttpServletRequest request, HttpRequest.Builder builder) {
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            if (HOP_BY_HOP_HEADERS.contains(name.toLowerCase())) {
                continue;
            }
            if ("cookie".equalsIgnoreCase(name)) {
                continue;
            }
            Enumeration<String> values = request.getHeaders(name);
            while (values.hasMoreElements()) {
                builder.header(name, values.nextElement());
            }
        }
    }

    private void copyResponseHeaders(HttpResponse<?> upstreamResponse, HttpServletResponse response) {
        upstreamResponse.headers().map().forEach((name, values) -> {
            if (HOP_BY_HOP_HEADERS.contains(name.toLowerCase())) {
                return;
            }
            for (String value : values) {
                response.addHeader(name, value);
            }
        });
    }
}
