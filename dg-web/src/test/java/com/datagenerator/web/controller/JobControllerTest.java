package com.datagenerator.web.controller;

import com.datagenerator.web.controller.JobController;
import com.datagenerator.web.dto.JobResponse;
import com.datagenerator.web.dto.JobStatus;
import com.datagenerator.web.dto.JobSubmitRequest;
import com.datagenerator.web.dto.JobSubmitResult;
import com.datagenerator.web.service.JobService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import com.datagenerator.web.testsupport.WebTestApplication;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = JobController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({WebTestApplication.class, JobControllerTest.ControllerTestConfig.class})
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

    @Test
    void submitJob_whenQueued_returns202WithJobId() throws Exception {
        JobResponse pending = new JobResponse();
        pending.setJobId("job-queued-1");
        pending.setStatus(JobStatus.PENDING);
        when(jobService.submit(any(JobSubmitRequest.class)))
                .thenReturn(new JobSubmitResult(pending, true));

        mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jobConfig":"jobs/single_customer.yaml",
                                 "writer":{"type":"csv","connection":"local-csv","mode":"insert"}}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("job-queued-1"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void submitJob_immediateRun_returns200Or202() throws Exception {
        when(jobService.submit(any(JobSubmitRequest.class)))
                .thenReturn(new JobSubmitResult(JobResponse.completed("job-sync-1", 50), false));

        mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jobConfig":"jobs/single_customer.yaml"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("job-sync-1"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }
}
