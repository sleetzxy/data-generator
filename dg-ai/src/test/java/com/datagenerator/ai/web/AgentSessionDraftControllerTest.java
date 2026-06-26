package com.datagenerator.ai.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.datagenerator.ai.application.AgentSessionApplicationService;
import com.datagenerator.ai.application.dto.SessionSnapshot;
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
    void getSession_default_omitsDraftYaml() throws Exception {
        when(agentSessionService.getSession("s1", false))
                .thenReturn(new SessionSnapshot(
                        "s1", "job-generator", "deepseek", Instant.parse("2026-01-01T00:00:00Z"),
                        null, true, false, true));

        mockMvc.perform(get("/api/v1/agent/sessions/s1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasDraft").value(true))
                .andExpect(jsonPath("$.draftValidated").value(true));
    }

    @Test
    void getSessionDraft_returnsYaml() throws Exception {
        when(agentSessionService.getSessionDraftYaml("s1")).thenReturn("writer:\n  type: csv");

        mockMvc.perform(get("/api/v1/agent/sessions/s1/draft"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draftYaml").value("writer:\n  type: csv"));
    }
}
