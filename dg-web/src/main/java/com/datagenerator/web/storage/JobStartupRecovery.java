package com.datagenerator.web.storage;

import com.datagenerator.web.dto.JobResponse;
import com.datagenerator.web.dto.JobStatus;
import com.datagenerator.web.service.JobLogStore;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class JobStartupRecovery {

    private final JobRepository jobRepository;
    private final JobLogStore jobLogStore;

    public JobStartupRecovery(JobRepository jobRepository, JobLogStore jobLogStore) {
        this.jobRepository = jobRepository;
        this.jobLogStore = jobLogStore;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(1)
    public void onReady() {
        recover();
    }

    void recover() {
        List<JobStatus> staleStatuses = List.of(JobStatus.PENDING, JobStatus.RUNNING);
        for (JobResponse job : jobRepository.findByStatusIn(staleStatuses)) {
            job.setStatus(JobStatus.CANCELLED);
            jobRepository.update(job);
            jobLogStore.warn(job.getJobId(), "服务重启，任务已取消");
        }
    }
}
