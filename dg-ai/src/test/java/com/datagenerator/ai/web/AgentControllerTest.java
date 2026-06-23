package com.datagenerator.ai.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.datagenerator.ai.service.AgentSessionService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;

@WebMvcTest(controllers = AgentController.class)
@Import(AgentController.class)
@TestPropertySource(properties = {
        "ai.enabled=true",
        "ai.server=true"
})
class AgentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AgentSessionService agentSessionService;

    @Test
    void listSkills_delegatesToService() throws Exception {
        when(agentSessionService.listSkills()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/agent/skills"))
                .andExpect(status().isOk());
    }
}
