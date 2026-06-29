package com.eventledger.gateway.controller;

import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.dto.ErrorResponse;
import com.eventledger.gateway.service.EventService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex,
                                                          HttpServletRequest request) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.toList());
        ErrorResponse response = buildErrorResponse(HttpStatus.BAD_REQUEST,
                "Validation failed", "Request validation failed", request, errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header("X-Trace-Id", response.getTraceId())
                .body(response);
    }

    @ExceptionHandler(EventService.EventNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleEventNotFound(EventService.EventNotFoundException ex,
                                                             HttpServletRequest request) {
        ErrorResponse response = buildErrorResponse(HttpStatus.NOT_FOUND,
                ex.getMessage(), "Not Found", request, null);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Trace-Id", response.getTraceId())
                .body(response);
    }

    @ExceptionHandler(AccountServiceClient.AccountServiceUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleAccountServiceUnavailable(AccountServiceClient.AccountServiceUnavailableException ex,
                                                                        HttpServletRequest request) {
        ErrorResponse response = buildErrorResponse(HttpStatus.SERVICE_UNAVAILABLE,
                "Account service unavailable", "Service Unavailable", request, List.of(ex.getMessage()));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("X-Trace-Id", response.getTraceId())
                .body(response);
    }

    @ExceptionHandler(AccountServiceClient.AccountServiceException.class)
    public ResponseEntity<ErrorResponse> handleAccountServiceError(AccountServiceClient.AccountServiceException ex,
                                                                  HttpServletRequest request) {
        ErrorResponse response = buildErrorResponse(HttpStatus.BAD_GATEWAY,
                "Account service error", "Bad Gateway", request, List.of(ex.getMessage()));
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .header("X-Trace-Id", response.getTraceId())
                .body(response);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(HttpMessageNotReadableException ex,
                                                          HttpServletRequest request) {
        ErrorResponse response = buildErrorResponse(HttpStatus.BAD_REQUEST,
                "Malformed request body", "Bad Request", request, List.of(ex.getMessage()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header("X-Trace-Id", response.getTraceId())
                .body(response);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                           HttpServletRequest request) {
        ErrorResponse response = buildErrorResponse(HttpStatus.BAD_REQUEST,
                ex.getMessage(), "Bad Request", request, null);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header("X-Trace-Id", response.getTraceId())
                .body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception", ex);
        ErrorResponse response = buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal Server Error", ex.getMessage(), request, null);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .header("X-Trace-Id", response.getTraceId())
                .body(response);
    }

    private ErrorResponse buildErrorResponse(HttpStatus status, String error, String message,
                                             HttpServletRequest request, List<String> details) {
        String traceId = MDC.get("traceId");
        if (traceId == null) {
            traceId = request.getHeader("X-Trace-Id");
        }
        ErrorResponse response = new ErrorResponse();
        response.setStatus(status.value());
        response.setError(error);
        response.setMessage(message);
        response.setTraceId(traceId);
        response.setDetails(details);
        return response;
    }
}
