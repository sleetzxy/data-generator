package com.datagenerator.ai.dto;

public class SseEvent {

    private final String event;
    private final String data;

    public SseEvent(String event, String data) {
        this.event = event;
        this.data = data;
    }

    public String getEvent() {
        return event;
    }

    public String getData() {
        return data;
    }
}
