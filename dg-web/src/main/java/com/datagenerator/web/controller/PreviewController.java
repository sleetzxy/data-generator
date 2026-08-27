package com.datagenerator.web.controller;

import com.datagenerator.web.dto.PreviewRequest;
import com.datagenerator.web.dto.PreviewResponse;
import com.datagenerator.web.service.TaskRunService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/preview")
public class PreviewController {

    private final TaskRunService taskRunService;

    public PreviewController(TaskRunService taskRunService) {
        this.taskRunService = taskRunService;
    }

    @PostMapping
    public ResponseEntity<PreviewResponse> preview(@RequestBody PreviewRequest request) {
        return ResponseEntity.ok(taskRunService.preview(request));
    }
}
