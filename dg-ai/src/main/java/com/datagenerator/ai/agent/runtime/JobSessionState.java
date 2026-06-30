package com.datagenerator.ai.agent.runtime;

import com.datagenerator.ai.application.session.AgentSession;
import com.datagenerator.ai.tool.model.DgWebModels.SchemaDetail;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Job Generator 的会话上下文键与访问器。 */
public final class JobSessionState {

    public static final String KEY_DRAFT_YAML = "job.draft.yaml";
    public static final String KEY_DRAFT_INCOMPLETE = "job.draft.incomplete";
    public static final String KEY_DRAFT_VALIDATED = "job.draft.validated";
    public static final String KEY_DRAFT_PERSISTED_IN_TURN = "job.draft.persistedInTurn";
    public static final String KEY_REFERENCE_YAMLS = "job.reference.yamls";
    public static final String KEY_REFERENCE_SCHEMAS = "job.reference.schemas";

    private JobSessionState() {
    }

    public static String draftYaml(AgentSession session) {
        return session.getContext(KEY_DRAFT_YAML, String.class);
    }

    public static void setDraftYaml(AgentSession session, String yaml) {
        if (yaml == null) {
            session.removeContext(KEY_DRAFT_YAML);
            return;
        }
        session.putContext(KEY_DRAFT_YAML, yaml);
    }

    public static boolean isDraftIncomplete(AgentSession session) {
        return Boolean.TRUE.equals(session.getContext(KEY_DRAFT_INCOMPLETE, Boolean.class));
    }

    public static void setDraftIncomplete(AgentSession session, boolean draftIncomplete) {
        session.putContext(KEY_DRAFT_INCOMPLETE, draftIncomplete);
    }

    public static boolean isDraftValidated(AgentSession session) {
        return Boolean.TRUE.equals(session.getContext(KEY_DRAFT_VALIDATED, Boolean.class));
    }

    public static void setDraftValidated(AgentSession session, boolean draftValidated) {
        session.putContext(KEY_DRAFT_VALIDATED, draftValidated);
    }

    public static boolean isDraftPersistedInTurn(AgentSession session) {
        return Boolean.TRUE.equals(session.getContext(KEY_DRAFT_PERSISTED_IN_TURN, Boolean.class));
    }

    public static void markDraftPersistedInTurn(AgentSession session) {
        session.putContext(KEY_DRAFT_PERSISTED_IN_TURN, true);
    }

    public static void clearDraftPersistedInTurn(AgentSession session) {
        session.putContext(KEY_DRAFT_PERSISTED_IN_TURN, false);
    }

    public static void putReferenceYaml(AgentSession session, String fileName, String yaml) {
        if (fileName == null || yaml == null || yaml.isBlank()) {
            return;
        }
        referenceYamls(session).put(fileName.trim(), yaml);
    }

    public static Map<String, String> referenceYamlsView(AgentSession session) {
        return Map.copyOf(referenceYamls(session));
    }

    public static void putReferenceSchema(AgentSession session, String name, SchemaDetail detail) {
        if (name == null || detail == null) {
            return;
        }
        referenceSchemas(session).put(name.trim(), detail);
    }

    public static Map<String, SchemaDetail> referenceSchemasView(AgentSession session) {
        return Map.copyOf(referenceSchemas(session));
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap<String, String> referenceYamls(AgentSession session) {
        ConcurrentHashMap<String, String> existing =
                session.getContext(KEY_REFERENCE_YAMLS, ConcurrentHashMap.class);
        if (existing != null) {
            return existing;
        }
        ConcurrentHashMap<String, String> created = new ConcurrentHashMap<>();
        session.putContext(KEY_REFERENCE_YAMLS, created);
        return created;
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap<String, SchemaDetail> referenceSchemas(AgentSession session) {
        ConcurrentHashMap<String, SchemaDetail> existing =
                session.getContext(KEY_REFERENCE_SCHEMAS, ConcurrentHashMap.class);
        if (existing != null) {
            return existing;
        }
        ConcurrentHashMap<String, SchemaDetail> created = new ConcurrentHashMap<>();
        session.putContext(KEY_REFERENCE_SCHEMAS, created);
        return created;
    }
}
