package com.datagenerator.web.controller;

import com.datagenerator.web.dto.TaskConfigResponse;
import com.datagenerator.web.dto.TaskScheduleResponse;
import com.datagenerator.web.config.DataGeneratorProperties;
import com.datagenerator.web.exception.GlobalExceptionHandler;
import com.datagenerator.web.exception.ReadOnlyScheduleException;
import com.datagenerator.web.service.TaskConfigService;
import com.datagenerator.web.service.TaskScheduleManager;
import com.datagenerator.web.service.TaskScheduleService;
import com.datagenerator.web.testsupport.WebTestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TaskConfigController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({WebTestApplication.class, GlobalExceptionHandler.class})
class TaskConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TaskConfigService jobDefinitionService;

    @MockBean
    private TaskScheduleService jobScheduleService;

    @MockBean
    private TaskScheduleManager scheduleManager;

    @MockBean
    private DataGeneratorProperties dataGeneratorProperties;

    @Test
    void listDefinitions_withNameFilter_delegatesToService() throws Exception {
        when(jobDefinitionService.list("演示"))
                .thenReturn(List.of(new TaskConfigResponse(
                        "demo_job",
                        "jobs/demo_job.yaml",
                        "demo_job",
                        "演示任务",
                        null,
                        false,
                        false)));

        mockMvc.perform(get("/api/v1/task-configs").param("name", "演示"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("演示任务"));
    }

    @Test
    void getDefinition_byName_returnsContent() throws Exception {
        when(jobDefinitionService.get("single_customer"))
                .thenReturn(new TaskConfigResponse(
                        "single_customer",
                        "jobs/single_customer.yaml",
                        "single_customer",
                        "单客户造数",
                        "id: single_customer\nname: 单客户造数",
                        true,
                        true));

        mockMvc.perform(get("/api/v1/task-configs/single_customer"))
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
                .thenReturn(new TaskConfigResponse(
                        "demo_job",
                        "jobs/demo_job.yaml",
                        "demo_job",
                        "演示任务",
                        null,
                        false,
                        false));
        when(jobScheduleService.resolveSchedule("jobs/demo_job.yaml", false))
                .thenReturn(new TaskScheduleResponse(true, "0 0 2 * * ?", true, "2026-06-07T02:00:00+08:00"));

        mockMvc.perform(get("/api/v1/task-configs/demo_job/schedule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.cron").value("0 0 2 * * ?"))
                .andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.nextRunAt").value("2026-06-07T02:00:00+08:00"));
    }

    @Test
    void getSchedule_builtinJob_returnsReadOnlySchedule() throws Exception {
        when(jobDefinitionService.get("builtin"))
                .thenReturn(new TaskConfigResponse(
                        "builtin",
                        "jobs/builtin.yaml",
                        "builtin",
                        "内置任务",
                        null,
                        true,
                        true));
        when(jobScheduleService.resolveSchedule("jobs/builtin.yaml", true))
                .thenReturn(new TaskScheduleResponse(true, "0 30 3 * * ?", false, "2026-06-07T03:30:00+08:00"));

        mockMvc.perform(get("/api/v1/task-configs/builtin/schedule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.cron").value("0 30 3 * * ?"))
                .andExpect(jsonPath("$.editable").value(false));
    }

    @Test
    void updateSchedule_builtinJob_returns403() throws Exception {
        when(jobDefinitionService.get("builtin"))
                .thenReturn(new TaskConfigResponse(
                        "builtin",
                        "jobs/builtin.yaml",
                        "builtin",
                        "内置任务",
                        null,
                        true,
                        true));
        when(jobScheduleService.saveSchedule(eq("jobs/builtin.yaml"), any()))
                .thenThrow(new ReadOnlyScheduleException("jobs/builtin.yaml"));

        mockMvc.perform(put("/api/v1/task-configs/builtin/schedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"cron\":\"0 0 2 * * ?\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Schedule is read-only for builtin job: jobs/builtin.yaml"));
    }

    @Test
    void updateSchedule_customJob_returnsSchedule() throws Exception {
        when(jobDefinitionService.get("demo_job"))
                .thenReturn(new TaskConfigResponse(
                        "demo_job",
                        "jobs/demo_job.yaml",
                        "demo_job",
                        "演示任务",
                        null,
                        false,
                        false));
        when(jobScheduleService.saveSchedule(eq("jobs/demo_job.yaml"), any()))
                .thenReturn(new TaskScheduleResponse(true, "0 0 2 * * ?", true, "2026-06-07T02:00:00+08:00"));

        mockMvc.perform(put("/api/v1/task-configs/demo_job/schedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"cron\":\"0 0 2 * * ?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.cron").value("0 0 2 * * ?"));

        verify(scheduleManager).reschedule("jobs/demo_job.yaml");
    }
}
