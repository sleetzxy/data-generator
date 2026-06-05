package com.datagenerator.api.controller;

import com.datagenerator.api.dto.JobResponse;
import com.datagenerator.api.dto.PreviewRequest;
import com.datagenerator.api.service.JobService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/preview")
public class PreviewController {

    private final JobService jobService;

    public PreviewController(JobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping
    public ResponseEntity<JobResponse> preview(@RequestBody PreviewRequest request) {
        return ResponseEntity.ok(jobService.preview(request));
    }
}
