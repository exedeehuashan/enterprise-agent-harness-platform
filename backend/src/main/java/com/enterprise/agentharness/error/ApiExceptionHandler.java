package com.enterprise.agentharness.error;

import com.enterprise.agentharness.task.TaskNotFoundException;
import com.enterprise.agentharness.task.InvalidTaskQueryException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    public record FieldViolation(String field, String message) {}
    public record ApiError(Instant timestamp, int status, String error, String message,
                           String path, List<FieldViolation> fieldErrors) {}

    private ResponseEntity<Object> error(HttpStatusCode status, String message, String path,
                                         List<FieldViolation> fields) {
        return ResponseEntity.status(status).body(new ApiError(Instant.now(), status.value(),
                HttpStatus.valueOf(status.value()).getReasonPhrase(), message, path, fields));
    }

    @ExceptionHandler(InvalidTaskQueryException.class)
    public ResponseEntity<Object> invalidQuery(InvalidTaskQueryException ex, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, ex.getMessage(), request.getRequestURI(), List.of());
    }

    @ExceptionHandler(com.enterprise.agentharness.task.TaskEditException.class)
    public ResponseEntity<Object> editError(com.enterprise.agentharness.task.TaskEditException ex, HttpServletRequest request) {
        return error(ex.status(), ex.getMessage(), request.getRequestURI(), List.of());
    }

    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Object> staleEdit(Exception ex, HttpServletRequest request) {
        return error(HttpStatus.PRECONDITION_FAILED, "Task changed; reload before editing", request.getRequestURI(), List.of());
    }

    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    public ResponseEntity<Object> invalidEdit(jakarta.validation.ConstraintViolationException ex, HttpServletRequest request) {
        var fields = ex.getConstraintViolations().stream()
                .map(v -> new FieldViolation(v.getPropertyPath().toString(), v.getMessage()))
                .sorted(java.util.Comparator.comparing(FieldViolation::field)).toList();
        return error(HttpStatus.BAD_REQUEST, "Request validation failed", request.getRequestURI(), fields);
    }

    @ExceptionHandler(com.enterprise.agentharness.run.RunException.class)
    public ResponseEntity<Object> runError(com.enterprise.agentharness.run.RunException ex, HttpServletRequest request) {
        return error(ex.status(), ex.getMessage(), request.getRequestURI(), List.of());
    }

    @ExceptionHandler(TaskNotFoundException.class)
    public ResponseEntity<Object> notFound(TaskNotFoundException ex, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, ex.getMessage(), request.getRequestURI(), List.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Object> invalidId(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "Invalid value for " + ex.getName(), request.getRequestURI(), List.of());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var fields = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new FieldViolation(e.getField(), e.getDefaultMessage())).toList();
        return error(status, "Request validation failed", path(request), fields);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return error(status, "Malformed or missing JSON request body", path(request), List.of());
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var response = error(status, HttpStatus.valueOf(status.value()).getReasonPhrase(), path(request), List.of());
        return new ResponseEntity<>(response.getBody(), headers, status);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> unexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled request failure", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request.getRequestURI(), List.of());
    }

    private String path(WebRequest request) {
        return ((ServletWebRequest) request).getRequest().getRequestURI();
    }
}
