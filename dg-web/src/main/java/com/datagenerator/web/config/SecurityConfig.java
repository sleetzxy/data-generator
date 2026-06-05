package com.datagenerator.web.config;

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
    SecurityFilterChain authenticatedFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                LOGIN_PAGE,
                                "/style.css",
                                "/api/v1/auth/login",
                                "/api/v1/health")
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
                .csrf(csrf -> csrf.disable());
        return http.build();
    }

    private void commenceAuthentication(
            HttpServletRequest request,
            HttpServletResponse response,
            org.springframework.security.core.AuthenticationException exception)
            throws java.io.IOException, jakarta.servlet.ServletException {
        if (request.getRequestURI().startsWith("/api/")) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        new LoginUrlAuthenticationEntryPoint(LOGIN_PAGE).commence(request, response, exception);
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
