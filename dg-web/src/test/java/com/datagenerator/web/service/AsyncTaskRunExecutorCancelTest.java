package com.datagenerator.web.service;

import com.datagenerator.web.config.TaskRunRuntimeSettings;
import com.datagenerator.web.dto.TaskRunStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncTaskRunExecutorCancelTest {

    private TaskRunServiceTestSupport.TaskRunServiceContext context;
    private AsyncTaskRunExecutor executor;

    @BeforeEach
    void setUp() {
        context = TaskRunServiceTestSupport.createContext(new TaskRunRuntimeSettings(5000, 1000, 2));
        executor = context.asyncTaskRunExecutor();
    }

    @Test
    void cancel_pendingJob_marksCancelled() throws InterruptedException {
        String runId = "job-cancel-test";
        executor.submit(runId, () -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });

        awaitStatus(runId, TaskRunStatus.RUNNING, TaskRunStatus.PENDING);
        assertThat(executor.cancel(runId)).isTrue();
        assertThat(context.taskRunRepository().findById(runId).orElseThrow().getStatus())
                .isEqualTo(TaskRunStatus.CANCELLED);
    }

    @Test
    void cancel_unknownJob_returnsFalse() {
        assertThat(executor.cancel("missing")).isFalse();
    }

    private void awaitStatus(String runId, TaskRunStatus... acceptable) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            var response = context.taskRunRepository().findById(runId);
            if (response.isPresent()) {
                for (TaskRunStatus status : acceptable) {
                    if (response.get().getStatus() == status) {
                        return;
                    }
                }
            }
            Thread.sleep(20);
        }
    }
}
