package com.wex.currency.exception;

import com.wex.currency.dto.ErrorResponse;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Translates exceptions into a consistent JSON {@link ErrorResponse}.
 *
 * <p>The status codes intentionally separate the two failure families the brief cares about:
 * a 422 means "we understood the request but cannot convert this purchase" (expected business
 * outcome), whereas a 503 means the Treasury dependency itself is down (infrastructure fault).
 * Conflating them would mislead clients and on-call engineers.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Bean Validation failures on the request body (e.g. description > 50 chars). */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "One or more fields are invalid", details);
    }

    /** Malformed JSON or an unparseable date — also a client error. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Request body is malformed or contains an invalid value", List.of());
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleBadParam(Exception ex) {
        return build(HttpStatus.BAD_REQUEST, "BAD_PARAMETER", ex.getMessage(), List.of());
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(TransactionNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), List.of());
    }

    @ExceptionHandler(CurrencyConversionException.class)
    public ResponseEntity<ErrorResponse> handleConversion(CurrencyConversionException ex) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "CONVERSION_UNAVAILABLE",
                ex.getMessage(), List.of());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyConflict(IdempotencyConflictException ex) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_CONFLICT",
                ex.getMessage(), List.of());
    }

    @ExceptionHandler(TreasuryUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleTreasuryDown(TreasuryUnavailableException ex) {
        log.error("Treasury dependency unavailable", ex);
        return build(HttpStatus.SERVICE_UNAVAILABLE, "TREASURY_UNAVAILABLE",
                ex.getMessage(), List.of());
    }

    /** Unknown path / missing static resource — a plain 404, not an internal error. */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(
            org.springframework.web.servlet.resource.NoResourceFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found", List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred", List.of());
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String error,
                                                String message, List<String> details) {
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(status.value(), error, message, details));
    }
}
