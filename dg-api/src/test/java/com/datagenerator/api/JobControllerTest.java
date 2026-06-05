package com.datagenerator.api;

import com.datagenerator.api.controller.JobController;
import com.datagenerator.api.dto.JobSubmitResult;
import com.datagenerator.api.dto.JobResponse;
import com.datagenerator.api.dto.JobSubmitRequest;
import com.datagenerator.api.service.JobService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = JobController.class)
@Import(JobControllerTest.ControllerTestConfig.class)
class JobControllerTest {

    @TestConfiguration
    @Import(JobController.class)
    static class ControllerTestConfig {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JobService jobService;

    @Test
    void submitJob_validRequest_delegatesToService() throws Exception {
        when(jobService.submit(any(JobSubmitRequest.class)))
                .thenReturn(new JobSubmitResult(JobResponse.completed("job-1", 100), false));

        mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jobConfig":"jobs/single_customer.yaml",
                                 "writer":{"type":"csv","connection":"local-csv","mode":"insert"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }
}
