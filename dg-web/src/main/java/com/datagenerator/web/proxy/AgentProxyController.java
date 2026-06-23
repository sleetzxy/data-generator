package com.datagenerator.web.proxy;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "data-generator.ai", name = "enabled", havingValue = "true")
public class AgentProxyController {

    private final AgentHttpProxy agentHttpProxy;

    public AgentProxyController(AgentHttpProxy agentHttpProxy) {
        this.agentHttpProxy = agentHttpProxy;
    }

    @RequestMapping("/api/v1/agent/**")
    public void proxy(HttpServletRequest request, HttpServletResponse response) throws IOException {
        agentHttpProxy.forward(request, response);
    }
}
