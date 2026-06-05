package com.datagenerator.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EndToEndTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void configDir(DynamicPropertyRegistry registry) {
        Path configs = Path.of(System.getProperty("user.dir")).resolve("..").resolve("configs").normalize();
        registry.add("data-generator.config-dir", () -> configs.toString());
    }

    @Test
    void preview_singleCustomer_returns200() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String body = """
                {
                  "jobConfig": "jobs/single_customer.yaml",
                  "preview": { "limit": 5 }
                }
                """;

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/preview",
                new HttpEntity<>(body, headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"COMPLETED\"");
    }
}
