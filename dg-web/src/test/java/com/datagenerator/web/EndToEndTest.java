package com.datagenerator.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = DataGeneratorApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EndToEndTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void preview_singleCustomer_returns200() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String body = """
                {
                  "jobConfig": "jobs/preview_smoke.yaml",
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
