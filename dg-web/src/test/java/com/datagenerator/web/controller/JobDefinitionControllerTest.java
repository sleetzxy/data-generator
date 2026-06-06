package com.datagenerator.web.controller;

import com.datagenerator.web.dto.JobDefinitionResponse;
import com.datagenerator.web.service.JobDefinitionService;
import com.datagenerator.web.testsupport.WebTestApplication;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = JobDefinitionController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(WebTestApplication.class)
class JobDefinitionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JobDefinitionService jobDefinitionService;

    @Test
    void getDefinition_byName_returnsContent() throws Exception {
        when(jobDefinitionService.get("single_customer"))
                .thenReturn(new JobDefinitionResponse(
                        "single_customer",
                        "jobs/single_customer.yaml",
                        "single_customer",
                        "单客户造数",
                        "id: single_customer\nname: 单客户造数",
                        true,
                        true));

        mockMvc.perform(get("/api/v1/job-definitions/single_customer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileName").value("single_customer"))
                .andExpect(jsonPath("$.name").value("单客户造数"))
                .andExpect(jsonPath("$.id").value("single_customer"))
                .andExpect(jsonPath("$.path").value("jobs/single_customer.yaml"))
                .andExpect(jsonPath("$.builtin").value(true))
                .andExpect(jsonPath("$.readOnly").value(true));
    }

    @Disabled("Task 10: schedule endpoints not yet implemented")
    @Test
    void updateSchedule_builtinJob_returns403() {
        // PUT /api/v1/job-definitions/{name}/schedule
        // ReadOnlyScheduleException → 403 FORBIDDEN via GlobalExceptionHandler
    }
}
