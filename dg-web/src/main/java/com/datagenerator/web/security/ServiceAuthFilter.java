package com.datagenerator.web.security;

import com.datagenerator.web.config.DataGeneratorProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 校验服务间调用请求头 {@link ServiceAuthSupport#HEADER_NAME}，通过后视为已认证并跳过登录。
 */
@Component
public class ServiceAuthFilter extends OncePerRequestFilter {

    private static final String SERVICE_PRINCIPAL = "dg-service";

    private final DataGeneratorProperties properties;

    public ServiceAuthFilter(DataGeneratorProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (ServiceAuthSupport.matches(request, properties)) {
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    SERVICE_PRINCIPAL,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_SERVICE")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        filterChain.doFilter(request, response);
    }
}
