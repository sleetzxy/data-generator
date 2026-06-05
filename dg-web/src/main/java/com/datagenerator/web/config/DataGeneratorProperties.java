package com.datagenerator.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "data-generator")
public class DataGeneratorProperties {

    private String configDir = "classpath:configs";
    private String writableConfigDir = "./data/configs";
    private Map<String, Map<String, Object>> connections = new HashMap<>();
    private JobProperties job = new JobProperties();
    private StorageProperties storage = new StorageProperties();

    public String getConfigDir() {
        return configDir;
    }

    public void setConfigDir(String configDir) {
        this.configDir = configDir;
    }

    public String getWritableConfigDir() {
        return writableConfigDir;
    }

    public void setWritableConfigDir(String writableConfigDir) {
        this.writableConfigDir = writableConfigDir;
    }

    public Map<String, Map<String, Object>> getConnections() {
        return connections;
    }

    public void setConnections(Map<String, Map<String, Object>> connections) {
        this.connections = connections;
    }

    public JobProperties getJob() {
        return job;
    }

    public void setJob(JobProperties job) {
        this.job = job;
    }

    public StorageProperties getStorage() {
        return storage;
    }

    public void setStorage(StorageProperties storage) {
        this.storage = storage;
    }

    public static class StorageProperties {

        private String sqlitePath = "./data/dg-jobs.db";

        public String getSqlitePath() {
            return sqlitePath;
        }

        public void setSqlitePath(String sqlitePath) {
            this.sqlitePath = sqlitePath;
        }
    }

    public static class JobProperties {

        private int syncThreshold = 5000;
        private int batchSize = 1000;
        private int threadPoolSize = 4;

        public int getSyncThreshold() {
            return syncThreshold;
        }

        public void setSyncThreshold(int syncThreshold) {
            this.syncThreshold = syncThreshold;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getThreadPoolSize() {
            return threadPoolSize;
        }

        public void setThreadPoolSize(int threadPoolSize) {
            this.threadPoolSize = threadPoolSize;
        }
    }
}
