package com.example.food.memory.mcp;

import com.example.food.security.AppRole;
import com.example.food.security.AuthPrincipal;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

public final class MemoryMcpUserContext {
    private static final String USER_ID = "userId";

    private MemoryMcpUserContext() { }

    public static McpTransportContext fromAuthentication(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthPrincipal principal)
                || principal.role() != AppRole.USER || principal.id() == null || principal.id() <= 0) {
            return McpTransportContext.EMPTY;
        }
        return McpTransportContext.create(Map.of(USER_ID, principal.id()));
    }

    public static Long requireUserId(McpTransportContext context) {
        Object value = context == null ? null : context.get(USER_ID);
        if (value instanceof Number number && number.longValue() > 0) {
            return number.longValue();
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "需要使用已登录的用户账号调用个人记忆工具");
    }
}
