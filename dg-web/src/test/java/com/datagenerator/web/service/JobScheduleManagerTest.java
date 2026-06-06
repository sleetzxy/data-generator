package com.datagenerator.web.service;

import com.datagenerator.web.dto.JobDefinitionResponse;
import com.datagenerator.web.dto.JobScheduleResponse;
import com.datagenerator.web.storage.JobScheduleRepository;
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
class JobScheduleManagerTest {

    private static final String CONFIG_PATH = "jobs/demo.yaml";

    @Mock
    private ThreadPoolTaskScheduler scheduler;

    @Mock
    private JobScheduleService scheduleService;

    @Mock
    private JobScheduleExecutor executor;

    @Mock
    private JobDefinitionService definitionService;

    @Mock
    private JobScheduleRepository scheduleRepository;

    @Mock
    private ScheduledFuture<?> scheduledFuture;

    private JobScheduleManager manager;

    @BeforeEach
    void setUp() {
        manager = new JobScheduleManager(
                scheduler, scheduleService, executor, definitionService, scheduleRepository);
    }

    @Test
    void reschedule_enabledWithValidCron_registersSchedule() {
        when(definitionService.list()).thenReturn(List.of(
                new JobDefinitionResponse("demo", CONFIG_PATH, "demo-id", "Demo", true)));
        when(scheduleService.resolveSchedule(CONFIG_PATH, true))
                .thenReturn(new JobScheduleResponse(true, "0 0 2 * * ?", false, null));
        doReturn(scheduledFuture).when(scheduler).schedule(any(Runnable.class), any(Trigger.class));

        manager.reschedule(CONFIG_PATH);

        verify(scheduler).schedule(any(Runnable.class), any(CronTrigger.class));
    }

    @Test
    void cancel_existingSchedule_cancelsFuture() {
        when(definitionService.list()).thenReturn(List.of(
                new JobDefinitionResponse("demo", CONFIG_PATH, "demo-id", "Demo", true)));
        when(scheduleService.resolveSchedule(CONFIG_PATH, true))
                .thenReturn(new JobScheduleResponse(true, "0 0 2 * * ?", false, null));
        doReturn(scheduledFuture).when(scheduler).schedule(any(Runnable.class), any(Trigger.class));

        manager.reschedule(CONFIG_PATH);
        manager.cancel(CONFIG_PATH);

        verify(scheduledFuture).cancel(false);
        verify(scheduler, times(1)).schedule(any(Runnable.class), any(Trigger.class));
    }
}
