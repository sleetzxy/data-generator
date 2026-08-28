package com.datagenerator.web.service;

import com.datagenerator.web.dto.TaskConfigResponse;
import com.datagenerator.web.dto.TaskScheduleResponse;
import com.datagenerator.web.storage.TaskScheduleRepository;
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
    private TaskConfigService definitionService;

    @Mock
    private TaskScheduleRepository scheduleRepository;

    @Mock
    private ScheduledFuture<?> scheduledFuture;

    private TaskScheduleManager manager;

    @BeforeEach
    void setUp() {
        manager = new TaskScheduleManager(
                scheduler, scheduleService, executor, definitionService, scheduleRepository);
    }

    @Test
    void reschedule_enabledWithValidCron_registersSchedule() {
        when(definitionService.list()).thenReturn(List.of(
                new TaskConfigResponse("demo", CONFIG_PATH, "demo-id", "Demo", true)));
        when(scheduleService.resolveSchedule(CONFIG_PATH, true))
                .thenReturn(new TaskScheduleResponse(true, "0 0 2 * * ?", false, null));
        doReturn(scheduledFuture).when(scheduler).schedule(any(Runnable.class), any(Trigger.class));

        manager.reschedule(CONFIG_PATH);

        verify(scheduler).schedule(any(Runnable.class), any(CronTrigger.class));
    }

    @Test
    void cancel_existingSchedule_cancelsFuture() {
        when(definitionService.list()).thenReturn(List.of(
                new TaskConfigResponse("demo", CONFIG_PATH, "demo-id", "Demo", true)));
        when(scheduleService.resolveSchedule(CONFIG_PATH, true))
                .thenReturn(new TaskScheduleResponse(true, "0 0 2 * * ?", false, null));
        doReturn(scheduledFuture).when(scheduler).schedule(any(Runnable.class), any(Trigger.class));

        manager.reschedule(CONFIG_PATH);
        manager.cancel(CONFIG_PATH);

        verify(scheduledFuture).cancel(false);
        verify(scheduler, times(1)).schedule(any(Runnable.class), any(Trigger.class));
    }
}
