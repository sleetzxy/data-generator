package com.datagenerator.ai.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.datagenerator.ai.application.AgentSessionApplicationService;
import com.datagenerator.ai.application.AgentSessionApplicationService.SessionSnapshot;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AgentController.class)
@Import(AgentController.class)
@TestPropertySource(properties = {"ai.enabled=true", "ai.server=true"})
class AgentSessionDraftControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AgentSessionApplicationService agentSessionService;

    @Test
    void getSession_returnsSessionMetadata() throws Exception {
        when(agentSessionService.getSession("s1"))
                .thenReturn(new SessionSnapshot(
                        "s1", "job-generator", "deepseek", Instant.parse("2026-01-01T00:00:00Z")));

        mockMvc.perform(get("/api/v1/agent/sessions/s1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("s1"))
                .andExpect(jsonPath("$.agentId").value("job-generator"))
                .andExpect(jsonPath("$.provider").value("deepseek"));
    }
}
