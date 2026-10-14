package com.datagenerator.web.dto;

import java.util.List;

/**
 * 远程库表列表。
 */
public record RemoteTableListResponse(List<String> tables) {}
