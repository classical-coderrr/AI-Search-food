package com.example.food.memory.mcp;

import com.example.food.security.AppRole;
import com.example.food.security.AuthPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public final class MemoryMcpSessionOwnershipFilter extends OncePerRequestFilter {
    private static final String SESSION_HEADER = "Mcp-Session-Id";

    private final MemoryMcpSessionOwnershipRegistry registry;

    public MemoryMcpSessionOwnershipFilter(MemoryMcpSessionOwnershipRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().endsWith(MemoryMcpServerConfiguration.ENDPOINT);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Long userId = authenticatedUserId(request);
        if (userId == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String sessionId = request.getHeader(SESSION_HEADER);
        if (sessionId != null && !registry.isOwnedBy(sessionId, userId)) {
            // Treat unknown and other users' sessions identically to avoid leaking session existence.
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        SessionHeaderResponseWrapper responseWrapper = new SessionHeaderResponseWrapper(response);
        filterChain.doFilter(request, responseWrapper);

        if (responseWrapper.sessionId() != null && response.getStatus() < HttpServletResponse.SC_BAD_REQUEST) {
            registry.register(responseWrapper.sessionId(), userId);
        }
        if ("DELETE".equalsIgnoreCase(request.getMethod())
                && sessionId != null && response.getStatus() < HttpServletResponse.SC_BAD_REQUEST) {
            registry.remove(sessionId);
        }
    }

    private Long authenticatedUserId(HttpServletRequest request) {
        Object requestPrincipal = request.getAttribute(AuthPrincipal.class.getName());
        AuthPrincipal principal = requestPrincipal instanceof AuthPrincipal authenticatedPrincipal
                ? authenticatedPrincipal : authenticationPrincipal();
        if (principal == null || principal.role() != AppRole.USER
                || principal.id() == null || principal.id() <= 0) {
            return null;
        }
        return principal.id();
    }

    private AuthPrincipal authenticationPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthPrincipal principal)) {
            return null;
        }
        return principal;
    }

    private static final class SessionHeaderResponseWrapper extends HttpServletResponseWrapper {
        private String sessionId;

        private SessionHeaderResponseWrapper(HttpServletResponse response) {
            super(response);
        }

        @Override
        public void setHeader(String name, String value) {
            super.setHeader(name, value);
            capture(name, value);
        }

        @Override
        public void addHeader(String name, String value) {
            super.addHeader(name, value);
            capture(name, value);
        }

        private void capture(String name, String value) {
            if (SESSION_HEADER.equalsIgnoreCase(name)) {
                sessionId = value;
            }
        }

        private String sessionId() {
            return sessionId;
        }
    }
}
