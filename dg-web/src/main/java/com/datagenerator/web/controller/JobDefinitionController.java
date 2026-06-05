package com.datagenerator.web.controller;

import com.datagenerator.web.dto.JobDefinitionRequest;
import com.datagenerator.web.dto.JobDefinitionResponse;
import com.datagenerator.web.service.JobDefinitionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/job-definitions")
public class JobDefinitionController {

    private final JobDefinitionService jobDefinitionService;

    public JobDefinitionController(JobDefinitionService jobDefinitionService) {
        this.jobDefinitionService = jobDefinitionService;
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
    public ResponseEntity<JobDefinitionResponse> create(@RequestBody JobDefinitionRequest request) {
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
}
