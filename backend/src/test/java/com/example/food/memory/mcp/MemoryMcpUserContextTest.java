package com.example.food.memory.mcp;

import com.example.food.security.AppRole;
import com.example.food.security.AuthPrincipal;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryMcpUserContextTest {
    @Test
    void contextUsesAuthenticatedUserPrincipal() {
        var principal = new AuthPrincipal(17L, "test-user", AppRole.USER, 0L);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));

        McpTransportContext context = MemoryMcpUserContext.fromAuthentication(authentication);

        assertThat(MemoryMcpUserContext.requireUserId(context)).isEqualTo(17L);
    }

    @Test
    void contextRejectsAdminPrincipalAndNeverAcceptsRequestArguments() {
        var admin = new AuthPrincipal(17L, "test-admin", AppRole.ADMIN, 0L);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                admin, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        McpTransportContext context = MemoryMcpUserContext.fromAuthentication(authentication);

        assertThatThrownBy(() -> MemoryMcpUserContext.requireUserId(context))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("已登录的用户账号");
    }

    @Test
    void contextRejectsUnauthenticatedRequest() {
        assertThatThrownBy(() -> MemoryMcpUserContext.requireUserId(McpTransportContext.EMPTY))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void mcpServerIsDisabledWhenNoExplicitEnablementIsConfigured() {
        new ApplicationContextRunner()
                .withUserConfiguration(MemoryMcpServerConfiguration.class)
                .run(context -> assertThat(context.getBeansOfType(HttpServletStreamableServerTransportProvider.class))
                        .isEmpty());
    }
}
