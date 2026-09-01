package com.datagenerator.web.dto;

/** 加载失败被跳过的任务配置信息 */
public record TaskConfigSkipInfo(String path, String reason) {
}
