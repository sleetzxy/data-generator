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
    private AuthProperties auth = new AuthProperties();
    private AiProperties ai = new AiProperties();
    private ServiceAuthProperties serviceAuth = new ServiceAuthProperties();

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

    public AuthProperties getAuth() {
        return auth;
    }

    public void setAuth(AuthProperties auth) {
        this.auth = auth;
    }

    public AiProperties getAi() {
        return ai;
    }

    public void setAi(AiProperties ai) {
        this.ai = ai;
    }

    public ServiceAuthProperties getServiceAuth() {
        return serviceAuth;
    }

    public void setServiceAuth(ServiceAuthProperties serviceAuth) {
        this.serviceAuth = serviceAuth;
    }

    public static class ServiceAuthProperties {

        /** 服务间调用共享密钥，dg-ai 通过 X-DG-Service-Auth 请求头携带 */
        private String token;

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }
    }

    public static class AiProperties {

        private boolean enabled = false;
        private String remoteBaseUrl;
        private java.time.Duration requestTimeout = java.time.Duration.ofSeconds(120);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getRemoteBaseUrl() {
            return remoteBaseUrl;
        }

        public void setRemoteBaseUrl(String remoteBaseUrl) {
            this.remoteBaseUrl = remoteBaseUrl;
        }

        public java.time.Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(java.time.Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
        }
    }

    public static class AuthProperties {

        private boolean enabled = true;
        private String username = "admin";
        private String password = "admin123";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    public static class StorageProperties {

        private String sqlitePath = "./data/dg-jobs.db";
        private String logDir = "./data/job-logs";

        public String getSqlitePath() {
            return sqlitePath;
        }

        public void setSqlitePath(String sqlitePath) {
            this.sqlitePath = sqlitePath;
        }

        public String getLogDir() {
            return logDir;
        }

        public void setLogDir(String logDir) {
            this.logDir = logDir;
        }
    }

    public static class JobProperties {

        private int syncThreshold = 5000;
        private int batchSize = 1000;
        private int threadPoolSize = 4;
        /** 造数并行度；0 表示沿用 threadPoolSize */
        private int generationParallelism = 0;

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

        public int getGenerationParallelism() {
            return generationParallelism;
        }

        public void setGenerationParallelism(int generationParallelism) {
            this.generationParallelism = generationParallelism;
        }
    }
}
