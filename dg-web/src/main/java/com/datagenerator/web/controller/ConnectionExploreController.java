package com.datagenerator.web.controller;

import com.datagenerator.web.dto.ConnectionProbeRequest;
import com.datagenerator.web.dto.ConnectionTestResponse;
import com.datagenerator.web.dto.RemoteColumnListResponse;
import com.datagenerator.web.dto.RemoteTableListResponse;
import com.datagenerator.web.service.ConnectionExploreService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 连接测通与库表探查 API（只读元数据，需登录认证）。
 */
@RestController
@RequestMapping("/api/v1/connections")
public class ConnectionExploreController {

    private final ConnectionExploreService connectionExploreService;

    public ConnectionExploreController(ConnectionExploreService connectionExploreService) {
        this.connectionExploreService = connectionExploreService;
    }

    @PostMapping("/test")
    public ResponseEntity<ConnectionTestResponse> test(@RequestBody ConnectionProbeRequest request) {
        return ResponseEntity.ok(connectionExploreService.test(request));
    }

    @PostMapping("/tables")
    public ResponseEntity<RemoteTableListResponse> listTables(
            @RequestBody ConnectionProbeRequest request) {
        return ResponseEntity.ok(connectionExploreService.listTables(request));
    }

    @PostMapping("/tables/{table}/columns")
    public ResponseEntity<RemoteColumnListResponse> listColumns(
            @PathVariable("table") String table, @RequestBody ConnectionProbeRequest request) {
        return ResponseEntity.ok(connectionExploreService.listColumns(request, table));
    }
}
