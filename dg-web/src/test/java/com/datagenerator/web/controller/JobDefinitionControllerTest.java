package com.datagenerator.web.controller;

import com.datagenerator.web.dto.JobDefinitionResponse;
import com.datagenerator.web.dto.JobScheduleResponse;
import com.datagenerator.web.exception.GlobalExceptionHandler;
import com.datagenerator.web.exception.ReadOnlyScheduleException;
import com.datagenerator.web.service.JobDefinitionService;
import com.datagenerator.web.service.JobScheduleManager;
import com.datagenerator.web.service.JobScheduleService;
import com.datagenerator.web.testsupport.WebTestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = JobDefinitionController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({WebTestApplication.class, GlobalExceptionHandler.class})
class JobDefinitionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JobDefinitionService jobDefinitionService;

    @MockBean
    private JobScheduleService jobScheduleService;

    @MockBean
    private JobScheduleManager scheduleManager;

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

    @Test
    void getSchedule_byName_returnsSchedule() throws Exception {
        when(jobDefinitionService.get("demo_job"))
                .thenReturn(new JobDefinitionResponse(
                        "demo_job",
                        "jobs/demo_job.yaml",
                        "demo_job",
                        "演示任务",
                        null,
                        false,
                        false));
        when(jobScheduleService.resolveSchedule("jobs/demo_job.yaml", false))
                .thenReturn(new JobScheduleResponse(true, "0 0 2 * * ?", true, "2026-06-07T02:00:00+08:00"));

        mockMvc.perform(get("/api/v1/job-definitions/demo_job/schedule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.cron").value("0 0 2 * * ?"))
                .andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.nextRunAt").value("2026-06-07T02:00:00+08:00"));
    }

    @Test
    void getSchedule_builtinJob_returnsReadOnlySchedule() throws Exception {
        when(jobDefinitionService.get("builtin"))
                .thenReturn(new JobDefinitionResponse(
                        "builtin",
                        "jobs/builtin.yaml",
                        "builtin",
                        "内置任务",
                        null,
                        true,
                        true));
        when(jobScheduleService.resolveSchedule("jobs/builtin.yaml", true))
                .thenReturn(new JobScheduleResponse(true, "0 30 3 * * ?", false, "2026-06-07T03:30:00+08:00"));

        mockMvc.perform(get("/api/v1/job-definitions/builtin/schedule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.cron").value("0 30 3 * * ?"))
                .andExpect(jsonPath("$.editable").value(false));
    }

    @Test
    void updateSchedule_builtinJob_returns403() throws Exception {
        when(jobDefinitionService.get("builtin"))
                .thenReturn(new JobDefinitionResponse(
                        "builtin",
                        "jobs/builtin.yaml",
                        "builtin",
                        "内置任务",
                        null,
                        true,
                        true));
        when(jobScheduleService.saveSchedule(eq("jobs/builtin.yaml"), any()))
                .thenThrow(new ReadOnlyScheduleException("jobs/builtin.yaml"));

        mockMvc.perform(put("/api/v1/job-definitions/builtin/schedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"cron\":\"0 0 2 * * ?\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Schedule is read-only for builtin job: jobs/builtin.yaml"));
    }

    @Test
    void updateSchedule_customJob_returnsSchedule() throws Exception {
        when(jobDefinitionService.get("demo_job"))
                .thenReturn(new JobDefinitionResponse(
                        "demo_job",
                        "jobs/demo_job.yaml",
                        "demo_job",
                        "演示任务",
                        null,
                        false,
                        false));
        when(jobScheduleService.saveSchedule(eq("jobs/demo_job.yaml"), any()))
                .thenReturn(new JobScheduleResponse(true, "0 0 2 * * ?", true, "2026-06-07T02:00:00+08:00"));

        mockMvc.perform(put("/api/v1/job-definitions/demo_job/schedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"cron\":\"0 0 2 * * ?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.cron").value("0 0 2 * * ?"));

        verify(scheduleManager).reschedule("jobs/demo_job.yaml");
    }
}
