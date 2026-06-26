package com.datagenerator.ai.model.adapter;

import com.datagenerator.ai.exception.EmptyStreamingChatResponseException;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 包装 {@link StreamingChatModel}，避免上游以 null {@link ChatResponse} 触发 LangChain4j
 * {@code AiServiceStreamingResponseHandler} NPE。
 */
public final class NullSafeStreamingChatModel implements StreamingChatModel {

    private static final Logger log = LoggerFactory.getLogger(NullSafeStreamingChatModel.class);

    private final StreamingChatModel delegate;

    public NullSafeStreamingChatModel(StreamingChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        delegate.chat(request, guard(handler));
    }

    @Override
    public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
        delegate.doChat(request, guard(handler));
    }

    @Override
    public void chat(String userMessage, StreamingChatResponseHandler handler) {
        delegate.chat(userMessage, guard(handler));
    }

    @Override
    public void chat(List<ChatMessage> messages, StreamingChatResponseHandler handler) {
        delegate.chat(messages, guard(handler));
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    @Override
    public List<ChatModelListener> listeners() {
        return delegate.listeners();
    }

    @Override
    public ModelProvider provider() {
        return delegate.provider();
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return delegate.supportedCapabilities();
    }

    private static StreamingChatResponseHandler guard(StreamingChatResponseHandler handler) {
        return new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                handler.onPartialResponse(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                if (completeResponse == null || completeResponse.aiMessage() == null) {
                    log.warn("Streaming model completed without ChatResponse/aiMessage");
                    handler.onError(new EmptyStreamingChatResponseException());
                    return;
                }
                handler.onCompleteResponse(completeResponse);
            }

            @Override
            public void onError(Throwable error) {
                handler.onError(error);
            }
        };
    }
}
