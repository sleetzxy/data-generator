package com.datagenerator.spi.model;

/**
 * Outcome of a write operation.
 */
public record WriteResult(int writtenCount, int failedCount) {
}
