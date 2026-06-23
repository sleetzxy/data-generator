package com.datagenerator.ai.web;

import com.datagenerator.ai.service.AgentSessionService;
import com.datagenerator.ai.dto.CreateSessionRequest;
import com.datagenerator.ai.dto.ProviderInfo;
import com.datagenerator.ai.dto.SendMessageRequest;
import com.datagenerator.ai.dto.SessionResponse;
import com.datagenerator.ai.dto.SkillInfo;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/agent")
@ConditionalOnProperty(prefix = "ai", name = "server", havingValue = "true")
public class AgentController {

    private final AgentSessionService agentSessionService;

    public AgentController(AgentSessionService agentSessionService) {
        this.agentSessionService = agentSessionService;
    }

    @GetMapping("/skills")
    public List<SkillInfo> listSkills() {
        return agentSessionService.listSkills();
    }

    @GetMapping("/providers")
    public List<ProviderInfo> listProviders() {
        return agentSessionService.listProviders();
    }

    @PostMapping("/sessions")
    public ResponseEntity<SessionResponse> createSession(@RequestBody CreateSessionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(agentSessionService.createSession(request));
    }

    @GetMapping("/sessions/{sessionId}")
    public SessionResponse getSession(@PathVariable String sessionId) {
        return agentSessionService.getSession(sessionId);
    }

    @DeleteMapping("/sessions/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSession(@PathVariable String sessionId) {
        agentSessionService.deleteSession(sessionId);
    }

    @PostMapping(value = "/sessions/{sessionId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessage(
            @PathVariable String sessionId,
            @RequestBody SendMessageRequest request) {
        SseEmitter emitter = AgentSseSupport.openStream(agentSessionService);
        AgentSseSupport.sendMessageAsync(agentSessionService, emitter, sessionId, request.getContent());
        return emitter;
    }
}
