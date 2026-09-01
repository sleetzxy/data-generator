package com.datagenerator.web.dto;

/** 单日运行统计：日期（yyyy-MM-dd，服务器本地时区）、运行次数与写入行数 */
public record DailyRunStat(String date, long runCount, long writtenRows) {
}
