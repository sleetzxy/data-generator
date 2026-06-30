package com.datagenerator.web.security;

import com.datagenerator.web.config.DataGeneratorProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class ServiceAuthSupport {

    public static final String HEADER_NAME = "X-DG-Service-Auth";

    private ServiceAuthSupport() {
    }

    public static boolean isConfigured(DataGeneratorProperties properties) {
        String token = properties.getServiceAuth().getToken();
        return token != null && !token.isBlank();
    }

    public static boolean matches(HttpServletRequest request, DataGeneratorProperties properties) {
        if (!isConfigured(properties)) {
            return false;
        }
        String provided = request.getHeader(HEADER_NAME);
        String expected = properties.getServiceAuth().getToken();
        if (provided == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
