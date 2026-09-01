package com.datagenerator.web.controller;

import com.datagenerator.web.dto.TaskConfigListResponse;
import com.datagenerator.web.dto.TaskConfigRequest;
import com.datagenerator.web.dto.TaskConfigResponse;
import com.datagenerator.web.dto.TaskScheduleRequest;
import com.datagenerator.web.dto.TaskScheduleResponse;
import com.datagenerator.web.service.TaskConfigService;
import com.datagenerator.web.service.TaskScheduleManager;
import com.datagenerator.web.service.TaskScheduleService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/task-configs")
public class TaskConfigController {

    private final TaskConfigService taskConfigService;
    private final TaskScheduleService taskScheduleService;
    private final TaskScheduleManager scheduleManager;

    public TaskConfigController(
            TaskConfigService taskConfigService,
            TaskScheduleService taskScheduleService,
            TaskScheduleManager scheduleManager) {
        this.taskConfigService = taskConfigService;
        this.taskScheduleService = taskScheduleService;
        this.scheduleManager = scheduleManager;
    }

    @GetMapping
    public TaskConfigListResponse list(
            @RequestParam(name = "name", required = false) String name,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {
        return taskConfigService.list(name, page, size);
    }

    @GetMapping("/{name}")
    public TaskConfigResponse get(@PathVariable("name") String name) {
        return taskConfigService.get(name);
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestBody TaskConfigRequest request,
            @RequestParam(name = "validateOnly", defaultValue = "false") boolean validateOnly) {
        if (validateOnly) {
            return ResponseEntity.ok(taskConfigService.validateYaml(request.getContent()));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(taskConfigService.create(request));
    }

    @PutMapping("/{name}")
    public TaskConfigResponse update(
            @PathVariable("name") String name,
            @RequestBody TaskConfigRequest request) {
        return taskConfigService.update(name, request);
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable("name") String name) {
        taskConfigService.delete(name);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{name}/schedule")
    public TaskScheduleResponse getSchedule(@PathVariable("name") String name) {
        TaskConfigResponse definition = taskConfigService.get(name);
        return taskScheduleService.resolveSchedule(definition.getPath(), definition.isBuiltin());
    }

    @PutMapping("/{name}/schedule")
    public TaskScheduleResponse updateSchedule(
            @PathVariable("name") String name,
            @RequestBody TaskScheduleRequest request) {
        TaskConfigResponse definition = taskConfigService.get(name);
        String configPath = definition.getPath();
        TaskScheduleResponse saved = taskScheduleService.saveSchedule(configPath, request);
        scheduleManager.reschedule(configPath);
        return saved;
    }
}
