package com.datagenerator.spi.model;

/**
 * Writer plugin configuration. Credential and connection fields are resolved by core before init.
 */
public record WriterConfig(
        String type,
        String connection,
        String mode,
        String table,
        String path,
        String url,
        String username,
        String password) {
}
