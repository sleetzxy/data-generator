package com.datagenerator.ai.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.langchain4j.model.chat.response.StreamingHandle;
import org.junit.jupiter.api.Test;

class StreamingHandleRegistryTest {

    @Test
    void cancel_invokesHandleCancel() {
        StreamingHandleRegistry registry = new StreamingHandleRegistry();
        StreamingHandle handle = mock(StreamingHandle.class);
        when(handle.isCancelled()).thenReturn(false);

        registry.register("session-1", handle);
        registry.cancel("session-1");

        verify(handle).cancel();
        registry.cancel("session-1");
    }

    @Test
    void unregister_removesWithoutCancel() {
        StreamingHandleRegistry registry = new StreamingHandleRegistry();
        StreamingHandle handle = mock(StreamingHandle.class);

        registry.register("session-1", handle);
        registry.unregister("session-1");
        registry.cancel("session-1");

        verify(handle, org.mockito.Mockito.never()).cancel();
    }
}
