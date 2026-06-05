package com.datagenerator.web.controller;

import com.datagenerator.web.dto.SchemaResponse;
import com.datagenerator.web.service.SchemaService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/schemas")
public class SchemaController {

    private final SchemaService schemaService;

    public SchemaController(SchemaService schemaService) {
        this.schemaService = schemaService;
    }

    @GetMapping
    public ResponseEntity<List<String>> listSchemas() {
        return ResponseEntity.ok(schemaService.listSchemas());
    }

    @GetMapping("/{name}")
    public ResponseEntity<SchemaResponse> getSchema(@PathVariable("name") String name) {
        return ResponseEntity.ok(schemaService.getSchema(name));
    }
}
