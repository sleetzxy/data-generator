package com.datagenerator.web.service;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跟踪运行中任务线程与取消标记，供同步/异步任务共享。
 */
@Component
public class JobCancellationRegistry {

    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Thread> runningThreads = new ConcurrentHashMap<>();

    public void registerRunning(String jobId) {
        runningThreads.put(jobId, Thread.currentThread());
    }

    public void unregisterRunning(String jobId) {
        runningThreads.remove(jobId);
        cancelled.remove(jobId);
    }

    public void markCancelled(String jobId) {
        cancelled.add(jobId);
    }

    public boolean isCancelled(String jobId) {
        return cancelled.contains(jobId);
    }

    /**
     * 标记取消并中断已登记的运行线程（同步任务路径）。
     *
     * @return 是否存在运行中线程
     */
    public boolean interruptRunning(String jobId) {
        markCancelled(jobId);
        Thread thread = runningThreads.get(jobId);
        if (thread != null) {
            thread.interrupt();
            return true;
        }
        return false;
    }

    public void clear(String jobId) {
        runningThreads.remove(jobId);
        cancelled.remove(jobId);
    }
}
