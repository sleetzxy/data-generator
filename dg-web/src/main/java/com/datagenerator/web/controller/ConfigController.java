package com.datagenerator.web.controller;

import com.datagenerator.web.config.DataGeneratorProperties;
import com.datagenerator.web.dto.ConnectionInfoResponse;
import java.util.Comparator;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/config")
public class ConfigController {

    private final DataGeneratorProperties properties;

    public ConfigController(DataGeneratorProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/connections")
    public List<ConnectionInfoResponse> listConnections() {
        return properties.getConnections().entrySet().stream()
                .map(entry -> new ConnectionInfoResponse(
                        entry.getKey(),
                        String.valueOf(entry.getValue().getOrDefault("type", "unknown"))))
                .sorted(Comparator.comparing(ConnectionInfoResponse::name))
                .toList();
    }
}
