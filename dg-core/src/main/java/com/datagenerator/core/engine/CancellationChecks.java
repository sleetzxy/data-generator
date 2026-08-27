package com.datagenerator.core.engine;

final class CancellationChecks {

    private CancellationChecks() {
    }

    static void throwIfCancelled(CancellationChecker checker) {
        if (checker != null && checker.isCancelled()) {
            throw new TaskRunCancelledException();
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new TaskRunCancelledException();
        }
    }
}
