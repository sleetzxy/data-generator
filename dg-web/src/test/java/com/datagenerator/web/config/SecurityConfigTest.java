package com.datagenerator.web.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "data-generator.auth.enabled=true",
        "data-generator.auth.username=testuser",
        "data-generator.auth.password=testpass",
        "data-generator.service-auth.token=service-secret"
})
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void loginPage_withoutCredentials_returnsOk() throws Exception {
        mockMvc.perform(get("/login.html"))
                .andExpect(status().isOk());
    }

    @Test
    void protectedApi_withoutCredentials_returnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/jobs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void formLogin_withValidCredentials_redirectsToHome() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .param("username", "testuser")
                        .param("password", "testpass"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void formLogin_withoutCsrf_returnsForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .param("username", "testuser")
                        .param("password", "testpass"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "testuser")
    void protectedApi_withAuthenticatedUser_returnsOk() throws Exception {
        mockMvc.perform(get("/api/v1/jobs"))
                .andExpect(status().isOk());
    }

    @Test
    void health_withoutCredentials_returnsOk() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk());
    }

    @Test
    void protectedApi_withServiceAuth_returnsOk() throws Exception {
        mockMvc.perform(get("/api/v1/config/connections")
                        .header("X-DG-Service-Auth", "service-secret"))
                .andExpect(status().isOk());
    }

    @Test
    void protectedApi_withInvalidServiceAuth_returnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/config/connections")
                        .header("X-DG-Service-Auth", "wrong-token"))
                .andExpect(status().isUnauthorized());
    }
}
