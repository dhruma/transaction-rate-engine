package com.wex.currency.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wex.currency.dto.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Minimal shared-secret API-key gate for {@code /api/**}.
 *
 * <p>Disabled by default ({@code app.auth.enabled=false}) so reviewers can exercise the service
 * without configuration. This is a deliberately simple stand-in: the README documents the
 * production approach (OAuth2/OIDC enforced at an API gateway, mTLS service-to-service, secrets
 * in a vault). The UI, Swagger UI and H2 console are intentionally left open for review.
 */
@Component
@ConditionalOnProperty(name = "app.auth.enabled", havingValue = "true")
public class ApiKeyFilter extends OncePerRequestFilter {

    private final String headerName;
    private final String expectedKey;
    private final ObjectMapper objectMapper;

    public ApiKeyFilter(@Value("${app.auth.header-name}") String headerName,
                        @Value("${app.auth.api-key}") String expectedKey,
                        ObjectMapper objectMapper) {
        this.headerName = headerName;
        this.expectedKey = expectedKey;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only the programmatic API is protected; review surfaces stay open.
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String provided = request.getHeader(headerName);
        if (expectedKey.equals(provided)) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ErrorResponse.of(
                HttpStatus.UNAUTHORIZED.value(), "UNAUTHORIZED",
                "Missing or invalid API key"));
    }
}
