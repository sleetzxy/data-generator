package com.datagenerator.app;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "data-generator")
public class DataGeneratorProperties {

    private String configDir = "classpath:configs";
    private Map<String, Map<String, Object>> connections = new HashMap<>();
    private JobProperties job = new JobProperties();

    public String getConfigDir() {
        return configDir;
    }

    public void setConfigDir(String configDir) {
        this.configDir = configDir;
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
