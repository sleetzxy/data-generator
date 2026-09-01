package com.datagenerator.web.controller;

import com.datagenerator.web.controller.TaskRunController;
import com.datagenerator.web.dto.TaskRunIndexResponse;
import com.datagenerator.web.dto.TaskRunResponse;
import com.datagenerator.web.dto.TaskRunStatsResponse;
import com.datagenerator.web.dto.TaskRunStatus;
import com.datagenerator.web.dto.TaskRunSubmitRequest;
import com.datagenerator.web.dto.TaskRunSubmitResult;
import com.datagenerator.web.config.DataGeneratorProperties;
import com.datagenerator.web.service.TaskRunService;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TaskRunController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({WebTestApplication.class, TaskRunControllerTest.ControllerTestConfig.class})
class TaskRunControllerTest {

    @TestConfiguration
    @Import(TaskRunController.class)
    static class ControllerTestConfig {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TaskRunService taskRunService;

    @MockBean
    private DataGeneratorProperties dataGeneratorProperties;

    @Test
    void submitTaskRun_validRequest_delegatesToService() throws Exception {
        when(taskRunService.submit(any(TaskRunSubmitRequest.class)))
                .thenReturn(new TaskRunSubmitResult(TaskRunResponse.completed("job-1", 100), false));

        mockMvc.perform(post("/api/v1/task-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"configPath":"task-configs/single_customer.yaml",
                                 "writer":{"type":"csv","connection":"local-csv","mode":"insert"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void submitTaskRun_whenQueued_returns202WithRunId() throws Exception {
        TaskRunResponse pending = new TaskRunResponse();
        pending.setRunId("job-queued-1");
        pending.setStatus(TaskRunStatus.PENDING);
        when(taskRunService.submit(any(TaskRunSubmitRequest.class)))
                .thenReturn(new TaskRunSubmitResult(pending, true));

        mockMvc.perform(post("/api/v1/task-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"configPath":"task-configs/single_customer.yaml",
                                 "writer":{"type":"csv","connection":"local-csv","mode":"insert"}}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value("job-queued-1"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void submitTaskRun_immediateRun_returns200Or202() throws Exception {
        when(taskRunService.submit(any(TaskRunSubmitRequest.class)))
                .thenReturn(new TaskRunSubmitResult(TaskRunResponse.completed("job-sync-1", 50), false));

        mockMvc.perform(post("/api/v1/task-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"configPath":"task-configs/single_customer.yaml"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("job-sync-1"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void getStats_delegatesToService() throws Exception {
        when(taskRunService.stats()).thenReturn(new TaskRunStatsResponse(
                10, 2, 1, 5, 1, 1, 1000, List.of(), List.of()));

        mockMvc.perform(get("/api/v1/task-runs/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRuns").value(10))
                .andExpect(jsonPath("$.totalWritten").value(1000));
    }

    @Test
    void getRunIndexes_delegatesToService() throws Exception {
        when(taskRunService.runIndexes()).thenReturn(new TaskRunIndexResponse(List.of(), List.of()));

        mockMvc.perform(get("/api/v1/task-runs/by-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestRuns").isArray())
                .andExpect(jsonPath("$.activeRuns").isArray());
    }
}
