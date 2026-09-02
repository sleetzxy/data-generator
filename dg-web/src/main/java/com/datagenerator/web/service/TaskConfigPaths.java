package com.datagenerator.web.service;

/** 任务配置路径与文件名的相互转换工具 */
public final class TaskConfigPaths {

    public static final String TASK_CONFIGS_DIR = "task-configs";

    private TaskConfigPaths() {
    }

    /** 文件名（不含扩展名）→ 配置路径（如 demo → task-configs/demo.yaml） */
    public static String toConfigPath(String fileName) {
        String normalized = fileName.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.startsWith(TASK_CONFIGS_DIR + "/")) {
            normalized = normalized.substring(TASK_CONFIGS_DIR.length() + 1);
        }
        if (normalized.endsWith(".yaml") || normalized.endsWith(".yml")) {
            return TASK_CONFIGS_DIR + "/" + normalized;
        }
        return TASK_CONFIGS_DIR + "/" + normalized + ".yaml";
    }

    /** 配置路径 → 文件名（task-configs/demo.yaml → demo） */
    public static String toFileName(String configPath) {
        String normalized = configPath.trim().replace('\\', '/');
        if (normalized.startsWith(TASK_CONFIGS_DIR + "/")) {
            normalized = normalized.substring(TASK_CONFIGS_DIR.length() + 1);
        }
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) {
            normalized = normalized.substring(slash + 1);
        }
        return normalized.replaceFirst("\\.ya?ml$", "");
    }
}
