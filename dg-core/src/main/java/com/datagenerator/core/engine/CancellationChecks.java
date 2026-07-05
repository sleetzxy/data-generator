package com.datagenerator.core.engine;

final class CancellationChecks {

    private CancellationChecks() {
    }

    static void throwIfCancelled(CancellationChecker checker) {
        if (checker != null && checker.isCancelled()) {
            throw new JobCancelledException();
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new JobCancelledException();
        }
    }
}
