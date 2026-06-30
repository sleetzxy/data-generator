package com.datagenerator.web.controller;

import com.datagenerator.web.dto.JobDefinitionRequest;
import com.datagenerator.web.dto.JobDefinitionResponse;
import com.datagenerator.web.dto.JobScheduleRequest;
import com.datagenerator.web.dto.JobScheduleResponse;
import com.datagenerator.web.service.JobDefinitionService;
import com.datagenerator.web.service.JobScheduleManager;
import com.datagenerator.web.service.JobScheduleService;
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

import java.util.List;

@RestController
@RequestMapping("/api/v1/job-definitions")
public class JobDefinitionController {

    private final JobDefinitionService jobDefinitionService;
    private final JobScheduleService jobScheduleService;
    private final JobScheduleManager scheduleManager;

    public JobDefinitionController(
            JobDefinitionService jobDefinitionService,
            JobScheduleService jobScheduleService,
            JobScheduleManager scheduleManager) {
        this.jobDefinitionService = jobDefinitionService;
        this.jobScheduleService = jobScheduleService;
        this.scheduleManager = scheduleManager;
    }

    @GetMapping
    public List<JobDefinitionResponse> list() {
        return jobDefinitionService.list();
    }

    @GetMapping("/{name}")
    public JobDefinitionResponse get(@PathVariable("name") String name) {
        return jobDefinitionService.get(name);
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestBody JobDefinitionRequest request,
            @RequestParam(name = "validateOnly", defaultValue = "false") boolean validateOnly) {
        if (validateOnly) {
            return ResponseEntity.ok(jobDefinitionService.validateYaml(request.getContent()));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(jobDefinitionService.create(request));
    }

    @PutMapping("/{name}")
    public JobDefinitionResponse update(
            @PathVariable("name") String name,
            @RequestBody JobDefinitionRequest request) {
        return jobDefinitionService.update(name, request);
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable("name") String name) {
        jobDefinitionService.delete(name);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{name}/schedule")
    public JobScheduleResponse getSchedule(@PathVariable("name") String name) {
        JobDefinitionResponse definition = jobDefinitionService.get(name);
        return jobScheduleService.resolveSchedule(definition.getPath(), definition.isBuiltin());
    }

    @PutMapping("/{name}/schedule")
    public JobScheduleResponse updateSchedule(
            @PathVariable("name") String name,
            @RequestBody JobScheduleRequest request) {
        JobDefinitionResponse definition = jobDefinitionService.get(name);
        String configPath = definition.getPath();
        JobScheduleResponse saved = jobScheduleService.saveSchedule(configPath, request);
        scheduleManager.reschedule(configPath);
        return saved;
    }
}
