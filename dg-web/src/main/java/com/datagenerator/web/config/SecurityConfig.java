package com.datagenerator.web.config;

import com.datagenerator.web.security.ServiceAuthFilter;
import com.datagenerator.web.security.ServiceAuthSupport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String LOGIN_PAGE = "/login.html";

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @ConditionalOnProperty(prefix = "data-generator.auth", name = "enabled", havingValue = "true", matchIfMissing = true)
    SecurityFilterChain authenticatedFilterChain(
            HttpSecurity http,
            DataGeneratorProperties properties,
            ServiceAuthFilter serviceAuthFilter) throws Exception {
        CsrfTokenRequestAttributeHandler requestHandler = new CsrfTokenRequestAttributeHandler();
        requestHandler.setCsrfRequestAttributeName(null);

        http
                .addFilterBefore(serviceAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                LOGIN_PAGE,
                                "/error",
                                "/css/style.css",
                                "/js/lib/csrf.js",
                                "/api/v1/auth/login",
                                "/api/v1/health",
                                "/api/v1/table-schemas/**",
                                "/api/v1/config/connections")
                        .permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage(LOGIN_PAGE)
                        .loginProcessingUrl("/api/v1/auth/login")
                        .defaultSuccessUrl("/", true)
                        .failureUrl("/login.html?error=true")
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/api/v1/auth/logout")
                        .logoutSuccessUrl("/login.html?logout=true")
                        .permitAll())
                .exceptionHandling(ex -> ex.authenticationEntryPoint(this::commenceAuthentication))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(requestHandler)
                        .ignoringRequestMatchers(request -> ServiceAuthSupport.matches(request, properties)));

        return http.build();
    }

    /**
     * API 未认证时直接写 401，避免 {@code sendError} 转发 /error 后再被当成页面请求 302 到登录页；
     * 否则浏览器 fetch 会静默跟随重定向，地址栏不变。
     */
    private void commenceAuthentication(
            HttpServletRequest request,
            HttpServletResponse response,
            org.springframework.security.core.AuthenticationException exception)
            throws java.io.IOException, jakarta.servlet.ServletException {
        if (isApiPath(request)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"未登录或会话已过期\"}");
            return;
        }
        new LoginUrlAuthenticationEntryPoint(LOGIN_PAGE).commence(request, response, exception);
    }

    /** 去掉 context-path 后判断是否 API 请求 */
    private static boolean isApiPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        return uri.startsWith("/api/");
    }

    @Bean
    @ConditionalOnProperty(prefix = "data-generator.auth", name = "enabled", havingValue = "false")
    SecurityFilterChain permitAllFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable());

        return http.build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "data-generator.auth", name = "enabled", havingValue = "true", matchIfMissing = true)
    UserDetailsService userDetailsService(
            DataGeneratorProperties properties,
            PasswordEncoder passwordEncoder) {
        DataGeneratorProperties.AuthProperties auth = properties.getAuth();

        return new InMemoryUserDetailsManager(
                User.builder()
                        .username(auth.getUsername())
                        .password(passwordEncoder.encode(auth.getPassword()))
                        .roles("USER")
                        .build());
    }
}
