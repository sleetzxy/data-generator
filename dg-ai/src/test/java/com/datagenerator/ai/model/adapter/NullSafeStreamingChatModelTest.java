package com.datagenerator.ai.model.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datagenerator.ai.exception.EmptyStreamingChatResponseException;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NullSafeStreamingChatModelTest {

    @Test
    void doChat_nullCompleteResponse_routesToOnError() {
        StreamingChatModel delegate = mock(StreamingChatModel.class);
        NullSafeStreamingChatModel model = new NullSafeStreamingChatModel(delegate);

        ArgumentCaptor<StreamingChatResponseHandler> handlerCaptor =
                ArgumentCaptor.forClass(StreamingChatResponseHandler.class);
        StreamingChatResponseHandler downstream = mock(StreamingChatResponseHandler.class);
        model.doChat(mock(ChatRequest.class), downstream);
        verify(delegate).doChat(any(), handlerCaptor.capture());

        handlerCaptor.getValue().onCompleteResponse(null);

        ArgumentCaptor<Throwable> errorCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(downstream).onError(errorCaptor.capture());
        verify(downstream, never()).onCompleteResponse(any());
        assertThat(errorCaptor.getValue()).isInstanceOf(EmptyStreamingChatResponseException.class);
    }

    @Test
    void doChat_validCompleteResponse_passesThrough() {
        StreamingChatModel delegate = mock(StreamingChatModel.class);
        NullSafeStreamingChatModel model = new NullSafeStreamingChatModel(delegate);

        ArgumentCaptor<StreamingChatResponseHandler> handlerCaptor =
                ArgumentCaptor.forClass(StreamingChatResponseHandler.class);
        StreamingChatResponseHandler downstream = mock(StreamingChatResponseHandler.class);
        model.doChat(mock(ChatRequest.class), downstream);
        verify(delegate).doChat(any(), handlerCaptor.capture());

        ChatResponse response = mock(ChatResponse.class);
        when(response.aiMessage()).thenReturn(dev.langchain4j.data.message.AiMessage.from("ok"));

        handlerCaptor.getValue().onCompleteResponse(response);

        verify(downstream).onCompleteResponse(response);
        verify(downstream, never()).onError(any());
    }

    @Test
    void emptyStreamingChatResponseException_hasFriendlyMessage() {
        assertThat(new EmptyStreamingChatResponseException())
                .hasMessageContaining("模型流式响应为空");
    }
}
