package com.datagenerator.web.storage;

import com.datagenerator.web.dto.TaskRunResponse;
import com.datagenerator.web.dto.TaskRunStatus;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TaskRunStartupRecovery {

    private final TaskRunRepository taskRunRepository;
    private final TaskRunLogRepository taskRunLogRepository;

    public TaskRunStartupRecovery(TaskRunRepository taskRunRepository, TaskRunLogRepository taskRunLogRepository) {
        this.taskRunRepository = taskRunRepository;
        this.taskRunLogRepository = taskRunLogRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(1)
    public void onReady() {
        recover();
    }

    void recover() {
        List<TaskRunStatus> staleStatuses = List.of(TaskRunStatus.PENDING, TaskRunStatus.RUNNING);
        for (TaskRunResponse run : taskRunRepository.findByStatusIn(staleStatuses)) {
            run.setStatus(TaskRunStatus.CANCELLED);
            taskRunRepository.update(run);
            taskRunLogRepository.warn(run.getRunId(), "服务重启，任务已取消");
        }
    }
}
