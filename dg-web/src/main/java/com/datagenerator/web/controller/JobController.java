package com.datagenerator.web.controller;

import com.datagenerator.web.dto.JobLogEntry;
import com.datagenerator.web.dto.JobResponse;
import com.datagenerator.web.dto.JobSubmitRequest;
import com.datagenerator.web.dto.JobSubmitResult;
import com.datagenerator.web.dto.JobSummaryResponse;
import com.datagenerator.web.service.JobService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @GetMapping
    public List<JobSummaryResponse> listJobs() {
        return jobService.listAll();
    }

    @PostMapping
    public ResponseEntity<JobResponse> submitJob(@RequestBody JobSubmitRequest request) {
        JobSubmitResult result = jobService.submit(request);
        if (result.async()) {
            return ResponseEntity.accepted().body(result.response());
        }
        return ResponseEntity.ok(result.response());
    }

    @GetMapping("/{id}")
    public ResponseEntity<JobResponse> getJob(@PathVariable("id") String jobId) {
        return ResponseEntity.ok(jobService.getById(jobId));
    }

    @GetMapping("/{id}/logs")
    public List<JobLogEntry> getJobLogs(@PathVariable("id") String jobId) {
        return jobService.getLogs(jobId);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancelJob(@PathVariable("id") String jobId) {
        jobService.cancel(jobId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/record")
    public ResponseEntity<Void> removeJobRecord(@PathVariable("id") String jobId) {
        jobService.remove(jobId);
        return ResponseEntity.noContent().build();
    }
}
