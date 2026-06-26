package com.datagenerator.ai.web;



import com.datagenerator.ai.application.AgentSessionApplicationService;

import com.datagenerator.ai.web.dto.request.ChatRequest;

import com.datagenerator.ai.web.dto.request.CreateSessionRequest;

import com.datagenerator.ai.web.dto.response.AgentInfo;
import com.datagenerator.ai.web.dto.response.ProviderInfo;

import com.datagenerator.ai.web.dto.response.DraftResponse;
import com.datagenerator.ai.web.dto.response.SessionResponse;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import jakarta.validation.Valid;
import java.util.concurrent.Executor;

@Validated
@RestController
@RequestMapping("/api/v1/agent")
@ConditionalOnProperty(prefix = "ai", name = {"server", "enabled"}, havingValue = "true")
public class AgentController {

    private final AgentSessionApplicationService agentSessionService;
    private final Executor agentExecutor;

    public AgentController(
            AgentSessionApplicationService agentSessionService,
            @Qualifier("agentExecutor") Executor agentExecutor) {
        this.agentSessionService = agentSessionService;
        this.agentExecutor = agentExecutor;
    }

    @GetMapping("/agents")
    public List<AgentInfo> listAgents() {
        return agentSessionService.listAgents();
    }

    @GetMapping("/providers")
    public List<ProviderInfo> listProviders() {
        return agentSessionService.listProviders();
    }

    @PostMapping("/sessions")
    public ResponseEntity<SessionResponse> createSession(@Valid @RequestBody CreateSessionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(WebDtoMapper.toSessionResponse(agentSessionService.createSession(request)));
    }

    @GetMapping("/sessions/{sessionId}")
    public SessionResponse getSession(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "false") boolean includeDraft) {
        return WebDtoMapper.toSessionResponse(agentSessionService.getSession(sessionId, includeDraft));
    }

    @GetMapping("/sessions/{sessionId}/draft")
    public DraftResponse getSessionDraft(@PathVariable String sessionId) {
        return new DraftResponse(agentSessionService.getSessionDraftYaml(sessionId));
    }

    @DeleteMapping("/sessions/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSession(@PathVariable String sessionId) {
        agentSessionService.deleteSession(sessionId);
    }

    @PostMapping(value = "/sessions/{sessionId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessage(
            @PathVariable String sessionId,
            @Valid @RequestBody ChatRequest request) {
        SseEmitter emitter = AgentSseSupport.openStream(agentSessionService, sessionId);
        AgentSseSupport.sendMessageAsync(agentSessionService, emitter, sessionId, request.getContent(), agentExecutor);
        return emitter;
    }
}

