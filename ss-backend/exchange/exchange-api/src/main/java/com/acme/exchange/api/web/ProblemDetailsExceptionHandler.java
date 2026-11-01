package com.acme.exchange.api.web;

import com.acme.exchange.domain.EnvelopeValidationException;
import com.acme.exchange.domain.FlowNotActiveException;
import com.acme.exchange.domain.MessageIdConflictException;
import com.acme.exchange.domain.MessageNotFoundException;
import com.acme.exchange.domain.PayloadTooLargeException;
import com.acme.exchange.domain.SchemaValidationException;
import com.acme.exchange.domain.StaleRevisionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ProblemDetailsExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsExceptionHandler.class);

    @ExceptionHandler(PayloadTooLargeException.class)
    ResponseEntity<ProblemDetail> handlePayloadTooLarge(PayloadTooLargeException exception) {
        return problem(HttpStatus.CONTENT_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                exception.getMessage(), false);
    }

    @ExceptionHandler(EnvelopeValidationException.class)
    ResponseEntity<ProblemDetail> handleEnvelopeValidation(EnvelopeValidationException exception) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "ENVELOPE_INVALID",
                exception.getMessage(), false);
    }

    @ExceptionHandler(SchemaValidationException.class)
    ResponseEntity<ProblemDetail> handleSchemaValidation(SchemaValidationException exception) {
        ResponseEntity<ProblemDetail> response = problem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "SCHEMA_VALIDATION_FAILED",
                exception.getMessage(),
                false);
        response.getBody().setProperty("errors", exception.errors());
        return response;
    }

    @ExceptionHandler(FlowNotActiveException.class)
    ResponseEntity<ProblemDetail> handleFlowNotActive(FlowNotActiveException exception) {
        return problem(HttpStatus.CONFLICT, "FLOW_NOT_ACTIVE",
                exception.getMessage(), false);
    }

    @ExceptionHandler(StaleRevisionException.class)
    ResponseEntity<ProblemDetail> handleStaleRevision(StaleRevisionException exception) {
        return problem(HttpStatus.CONFLICT, "STALE_REVISION",
                exception.getMessage(), false);
    }

    @ExceptionHandler(MessageIdConflictException.class)
    ResponseEntity<ProblemDetail> handleMessageIdConflict(MessageIdConflictException exception) {
        ResponseEntity<ProblemDetail> response = problem(HttpStatus.CONFLICT, "MESSAGE_ID_CONFLICT",
                exception.getMessage(), false);
        response.getBody().setProperty("messageId", exception.messageId());
        response.getBody().setProperty("acceptedAt", exception.acceptedAt().toString());
        return response;
    }

    @ExceptionHandler(MessageNotFoundException.class)
    ResponseEntity<ProblemDetail> handleMessageNotFound(MessageNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "MESSAGE_NOT_FOUND",
                exception.getMessage(), false);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_JSON",
                "Request body is not valid JSON", false);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException exception) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                exception.getMessage(), false);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception exception) {
        log.error("Unhandled exchange API error", exception);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred", true);
    }

    private ResponseEntity<ProblemDetail> problem(
            HttpStatus status,
            String code,
            String detail,
            boolean retryable
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        problem.setProperty("retryable", retryable);
        String correlationId = MDC.get("correlationId");
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
