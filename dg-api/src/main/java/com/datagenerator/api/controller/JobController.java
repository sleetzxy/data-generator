package com.datagenerator.api.controller;

import com.datagenerator.api.dto.JobResponse;
import com.datagenerator.api.dto.JobSubmitRequest;
import com.datagenerator.api.service.JobService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping
    public ResponseEntity<JobResponse> submitJob(@RequestBody JobSubmitRequest request) {
        return ResponseEntity.ok(jobService.submit(request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<JobResponse> getJob(@PathVariable("id") String jobId) {
        return ResponseEntity.ok(jobService.getById(jobId));
    }
}
