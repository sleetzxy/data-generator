package com.datagenerator.web.controller;

import com.datagenerator.web.config.DataGeneratorProperties;
import com.datagenerator.web.dto.ConnectionProbeRequest;
import com.datagenerator.web.dto.ConnectionTestResponse;
import com.datagenerator.web.dto.RemoteColumnListResponse;
import com.datagenerator.web.dto.RemoteTableListResponse;
import com.datagenerator.web.exception.GlobalExceptionHandler;
import com.datagenerator.web.service.ConnectionExploreService;
import com.datagenerator.web.testsupport.WebTestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ConnectionExploreController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({WebTestApplication.class, GlobalExceptionHandler.class})
class ConnectionExploreControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ConnectionExploreService connectionExploreService;

    @MockBean
    private DataGeneratorProperties dataGeneratorProperties;

    @Test
    void test_validRequest_returnsOk() throws Exception {
        when(connectionExploreService.test(any(ConnectionProbeRequest.class)))
                .thenReturn(ConnectionTestResponse.success());

        mockMvc.perform(post("/api/v1/connections/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "postgresql",
                                  "url": "jdbc:postgresql://localhost/db",
                                  "username": "u",
                                  "password": "p"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void test_serviceFails_returns400ChineseMessage() throws Exception {
        when(connectionExploreService.test(any(ConnectionProbeRequest.class)))
                .thenThrow(new IllegalArgumentException("连接测试失败"));

        mockMvc.perform(post("/api/v1/connections/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "postgresql",
                                  "url": "jdbc:postgresql://bad/db"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("连接测试失败"));
    }

    @Test
    void listTables_validRequest_returnsTables() throws Exception {
        when(connectionExploreService.listTables(any(ConnectionProbeRequest.class)))
                .thenReturn(new RemoteTableListResponse(List.of("customers", "orders")));

        mockMvc.perform(post("/api/v1/connections/tables")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "postgresql",
                                  "url": "jdbc:postgresql://localhost/db",
                                  "schema": "public",
                                  "pattern": "cust%"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[0]").value("customers"))
                .andExpect(jsonPath("$.tables[1]").value("orders"));
    }

    @Test
    void listColumns_validRequest_returnsColumns() throws Exception {
        when(connectionExploreService.listColumns(any(ConnectionProbeRequest.class), eq("customers")))
                .thenReturn(new RemoteColumnListResponse(List.of(
                        new RemoteColumnListResponse.Column("id", "int8", false, true),
                        new RemoteColumnListResponse.Column("name", "varchar", true, false))));

        mockMvc.perform(post("/api/v1/connections/tables/customers/columns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "postgresql",
                                  "url": "jdbc:postgresql://localhost/db"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[0].name").value("id"))
                .andExpect(jsonPath("$.columns[0].typeName").value("int8"))
                .andExpect(jsonPath("$.columns[0].nullable").value(false))
                .andExpect(jsonPath("$.columns[0].primaryKey").value(true))
                .andExpect(jsonPath("$.columns[1].name").value("name"));
    }

    @Test
    void listColumns_serviceFails_returns400() throws Exception {
        when(connectionExploreService.listColumns(any(ConnectionProbeRequest.class), eq("missing")))
                .thenThrow(new IllegalArgumentException("列出字段失败"));

        mockMvc.perform(post("/api/v1/connections/tables/missing/columns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "postgresql",
                                  "url": "jdbc:postgresql://localhost/db"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("列出字段失败"));
    }
}
