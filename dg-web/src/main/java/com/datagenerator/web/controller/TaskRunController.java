package com.datagenerator.web.controller;

import com.datagenerator.web.dto.TaskRunListResponse;
import com.datagenerator.web.dto.TaskRunLogEntry;
import com.datagenerator.web.dto.TaskRunResponse;
import com.datagenerator.web.dto.TaskRunSubmitRequest;
import com.datagenerator.web.dto.TaskRunSubmitResult;
import com.datagenerator.web.dto.TaskRunSummaryResponse;
import com.datagenerator.web.service.TaskRunService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/task-runs")
public class TaskRunController {

    private final TaskRunService taskRunService;

    public TaskRunController(TaskRunService taskRunService) {
        this.taskRunService = taskRunService;
    }

    @GetMapping
    public TaskRunListResponse listTaskRuns(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return taskRunService.list(page, size);
    }

    @PostMapping
    public ResponseEntity<TaskRunResponse> submitTaskRun(@RequestBody TaskRunSubmitRequest request) {
        TaskRunSubmitResult result = taskRunService.submit(request);
        if (result.async()) {
            return ResponseEntity.accepted().body(result.response());
        }
        return ResponseEntity.ok(result.response());
    }

    @GetMapping("/{id}")
    public ResponseEntity<TaskRunResponse> getTaskRun(@PathVariable("id") String runId) {
        return ResponseEntity.ok(taskRunService.getById(runId));
    }

    @GetMapping("/{id}/logs")
    public List<TaskRunLogEntry> getTaskRunLogs(@PathVariable("id") String runId) {
        return taskRunService.getLogs(runId);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancelTaskRun(@PathVariable("id") String runId) {
        taskRunService.cancel(runId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/record")
    public ResponseEntity<Void> removeTaskRunRecord(@PathVariable("id") String runId) {
        taskRunService.remove(runId);
        return ResponseEntity.noContent().build();
    }
}
