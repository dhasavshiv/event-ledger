package com.eventledger.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    private Instant timestamp = Instant.now();
    private String service = "event-gateway";
    private int status;
    private String error;
    private String message;
    private String traceId;
    private List<String> details;

    public ErrorResponse() {
    }

    public ErrorResponse(int status, String error, String message, String traceId, List<String> details) {
        this.status = status;
        this.error = error;
        this.message = message;
        this.traceId = traceId;
        this.details = details;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public String getService() {
        return service;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public List<String> getDetails() {
        return details;
    }

    public void setDetails(List<String> details) {
        this.details = details;
    }
}
