package com.datagenerator.web.controller;

import com.datagenerator.web.dto.TableSchemaResponse;
import com.datagenerator.web.service.TableSchemaService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/table-schemas")
public class TableSchemaController {

    private final TableSchemaService tableSchemaService;

    public TableSchemaController(TableSchemaService tableSchemaService) {
        this.tableSchemaService = tableSchemaService;
    }

    @GetMapping
    public ResponseEntity<List<String>> listSchemas() {
        return ResponseEntity.ok(tableSchemaService.listSchemas());
    }

    @GetMapping("/{name}")
    public ResponseEntity<TableSchemaResponse> getSchema(@PathVariable("name") String name) {
        return ResponseEntity.ok(tableSchemaService.getSchema(name));
    }
}
