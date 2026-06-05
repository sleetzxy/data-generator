package com.datagenerator.spi.model;

/**
 * Reader plugin configuration. Credential and connection fields are resolved by core before init.
 */
public record ReaderConfig(
        String type,
        String connection,
        String query,
        String path,
        String url,
        String username,
        String password) {
}
