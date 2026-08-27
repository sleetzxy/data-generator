package com.datagenerator.web.service;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跟踪运行中任务线程与取消标记，供同步/异步任务共享。
 */
@Component
public class TaskRunCancellationRegistry {

    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Thread> runningThreads = new ConcurrentHashMap<>();

    public void registerRunning(String runId) {
        runningThreads.put(runId, Thread.currentThread());
    }

    public void unregisterRunning(String runId) {
        runningThreads.remove(runId);
        cancelled.remove(runId);
    }

    public void markCancelled(String runId) {
        cancelled.add(runId);
    }

    public boolean isCancelled(String runId) {
        return cancelled.contains(runId);
    }

    /**
     * 标记取消并中断已登记的运行线程（同步任务路径）。
     *
     * @return 是否存在运行中线程
     */
    public boolean interruptRunning(String runId) {
        markCancelled(runId);
        Thread thread = runningThreads.get(runId);
        if (thread != null) {
            thread.interrupt();
            return true;
        }
        return false;
    }

    public void clear(String runId) {
        runningThreads.remove(runId);
        cancelled.remove(runId);
    }
}
