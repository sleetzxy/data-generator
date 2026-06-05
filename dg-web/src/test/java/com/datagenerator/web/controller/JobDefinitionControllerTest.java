package com.datagenerator.web.controller;

import com.datagenerator.web.dto.JobDefinitionResponse;
import com.datagenerator.web.service.JobDefinitionService;
import com.datagenerator.web.testsupport.WebTestApplication;
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
                        "job: single_customer",
                        true));

        mockMvc.perform(get("/api/v1/job-definitions/single_customer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("single_customer"))
                .andExpect(jsonPath("$.path").value("jobs/single_customer.yaml"));
    }
}
