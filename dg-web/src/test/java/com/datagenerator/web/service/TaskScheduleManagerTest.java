package com.datagenerator.web.service;

import com.datagenerator.web.dto.TaskScheduleResponse;
import com.datagenerator.web.storage.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

import java.util.List;
import java.util.concurrent.ScheduledFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskScheduleManagerTest {

    private static final String CONFIG_PATH = "task-configs/demo.yaml";

    @Mock
    private ThreadPoolTaskScheduler scheduler;

    @Mock
    private TaskScheduleService scheduleService;

    @Mock
    private TaskRunQueueExecutor executor;

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private ScheduledFuture<?> scheduledFuture;

    private TaskScheduleManager manager;

    @BeforeEach
    void setUp() {
        manager = new TaskScheduleManager(scheduler, scheduleService, executor, taskRepository);
    }

    @Test
    void reloadAll_enabledTask_registersSchedule() {
        when(taskRepository.findAllEnabledSchedules())
                .thenReturn(List.of(enabledRecord("demo")));
        when(scheduleService.resolveSchedule(CONFIG_PATH))
                .thenReturn(new TaskScheduleResponse(
                        true, "0 0 2 * * ?", "2026-09-03T02:00:00+08:00"));
        doReturn(scheduledFuture).when(scheduler).schedule(any(Runnable.class), any(Trigger.class));

        manager.reloadAll();

        verify(scheduler).schedule(any(Runnable.class), any(CronTrigger.class));
    }

    @Test
    void reschedule_disabledTask_doesNotRegister() {
        when(scheduleService.resolveSchedule(CONFIG_PATH))
                .thenReturn(new TaskScheduleResponse(false, null, null));

        manager.reschedule(CONFIG_PATH);

        verify(scheduler, never()).schedule(any(Runnable.class), any(Trigger.class));
    }

    @Test
    void cancel_existingSchedule_cancelsFuture() {
        when(scheduleService.resolveSchedule(CONFIG_PATH))
                .thenReturn(new TaskScheduleResponse(
                        true, "0 0 2 * * ?", "2026-09-03T02:00:00+08:00"));
        doReturn(scheduledFuture).when(scheduler).schedule(any(Runnable.class), any(Trigger.class));

        manager.reschedule(CONFIG_PATH);
        manager.cancel(CONFIG_PATH);

        verify(scheduledFuture).cancel(false);
        verify(scheduler, times(1)).schedule(any(Runnable.class), any(Trigger.class));
    }

    private static TaskRepository.TaskRecord enabledRecord(String fileName) {
        return new TaskRepository.TaskRecord(
                fileName, fileName, "演示任务", true, "0 0 2 * * ?",
                "2026-09-02T10:00:00Z", null);
    }
}
