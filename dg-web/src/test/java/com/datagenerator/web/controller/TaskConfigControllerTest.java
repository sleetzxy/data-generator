package com.datagenerator.web.controller;

import com.datagenerator.web.config.DataGeneratorProperties;
import com.datagenerator.web.dto.TaskConfigListResponse;
import com.datagenerator.web.dto.TaskConfigResponse;
import com.datagenerator.web.dto.TaskConfigValidationResponse;
import com.datagenerator.web.dto.TaskScheduleResponse;
import com.datagenerator.web.exception.GlobalExceptionHandler;
import com.datagenerator.web.exception.TaskConfigNotFoundException;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    private TaskConfigService taskConfigService;

    @MockBean
    private TaskScheduleService taskScheduleService;

    @MockBean
    private TaskScheduleManager scheduleManager;

    @MockBean
    private DataGeneratorProperties dataGeneratorProperties;

    @Test
    void list_withNameFilter_delegatesToService() throws Exception {
        when(taskConfigService.list("演示", null, null))
                .thenReturn(new TaskConfigListResponse(
                        List.of(response("demo_job", "演示任务")), 1L, 1, 20));

        mockMvc.perform(get("/api/v1/task-configs").param("name", "演示"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].name").value("演示任务"));
    }

    @Test
    void getDefinition_byName_returnsContent() throws Exception {
        when(taskConfigService.get("single_customer"))
                .thenReturn(response("single_customer", "单客户造数"));

        mockMvc.perform(get("/api/v1/task-configs/single_customer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileName").value("single_customer"))
                .andExpect(jsonPath("$.name").value("单客户造数"))
                .andExpect(jsonPath("$.id").value("single_customer"))
                .andExpect(jsonPath("$.path").value("task-configs/single_customer.yaml"));
    }

    @Test
    void getDefinition_missing_returns404() throws Exception {
        when(taskConfigService.get("ghost"))
                .thenThrow(new TaskConfigNotFoundException("Task config not found: ghost"));

        mockMvc.perform(get("/api/v1/task-configs/ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Task config not found: ghost"));
    }

    @Test
    void create_withRequestBody_returnsCreated() throws Exception {
        when(taskConfigService.create(any()))
                .thenReturn(response("demo_job", "演示任务"));

        mockMvc.perform(post("/api/v1/task-configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fileName": "demo_job",
                                  "displayName": "演示任务",
                                  "content": "tables: []"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileName").value("demo_job"))
                .andExpect(jsonPath("$.name").value("演示任务"));
    }

    @Test
    void getSchedule_byName_returnsSchedule() throws Exception {
        when(taskScheduleService.resolveSchedule("task-configs/demo_job.yaml"))
                .thenReturn(new TaskScheduleResponse(
                        true, "0 0 2 * * ?", "2026-09-03T02:00:00+08:00"));

        mockMvc.perform(get("/api/v1/task-configs/demo_job/schedule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.cron").value("0 0 2 * * ?"))
                .andExpect(jsonPath("$.nextRunAt").value("2026-09-03T02:00:00+08:00"));
    }

    @Test
    void getSchedule_missingRow_returns404() throws Exception {
        when(taskScheduleService.resolveSchedule("task-configs/ghost.yaml"))
                .thenThrow(new TaskConfigNotFoundException("Task config not found: ghost"));

        mockMvc.perform(get("/api/v1/task-configs/ghost/schedule"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Task config not found: ghost"));
    }

    @Test
    void updateSchedule_withRequestBody_persistsAndReschedules() throws Exception {
        when(taskScheduleService.saveSchedule(eq("task-configs/demo_job.yaml"), any()))
                .thenReturn(new TaskScheduleResponse(
                        true, "0 0 2 * * ?", "2026-09-03T02:00:00+08:00"));

        mockMvc.perform(put("/api/v1/task-configs/demo_job/schedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"cron\":\"0 0 2 * * ?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.cron").value("0 0 2 * * ?"));

        verify(scheduleManager).reschedule("task-configs/demo_job.yaml");
    }

    @Test
    void create_validateOnlyNonMappingContent_returns200Invalid() throws Exception {
        when(taskConfigService.validateYaml(any()))
                .thenReturn(TaskConfigValidationResponse.fail(
                        List.of("Task config YAML must be a mapping")));

        mockMvc.perform(post("/api/v1/task-configs")
                        .param("validateOnly", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "content": "just a plain string"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[0]")
                        .value("Task config YAML must be a mapping"));
    }

    @Test
    void deleteDefinition_existing_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/task-configs/demo_job"))
                .andExpect(status().isNoContent());

        verify(taskConfigService).delete("demo_job");
    }

    @Test
    void deleteDefinition_missing_returns404() throws Exception {
        doThrow(new TaskConfigNotFoundException("Task config not found: ghost"))
                .when(taskConfigService).delete("ghost");

        mockMvc.perform(delete("/api/v1/task-configs/ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Task config not found: ghost"));
    }

    private static TaskConfigResponse response(String fileName, String name) {
        return new TaskConfigResponse(
                fileName, "task-configs/" + fileName + ".yaml", fileName, name, null);
    }
}
