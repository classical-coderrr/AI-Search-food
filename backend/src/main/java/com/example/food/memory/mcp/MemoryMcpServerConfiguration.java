package com.example.food.memory.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;

@Configuration
@ConditionalOnProperty(prefix = "app.memory.mcp", name = "enabled", havingValue = "true")
public class MemoryMcpServerConfiguration {
    public static final String ENDPOINT = "/api/memory/mcp";

    @Bean
    HttpServletStreamableServerTransportProvider memoryMcpTransportProvider() {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(McpJsonDefaults.getMapper())
                .mcpEndpoint(ENDPOINT)
                .contextExtractor(request -> MemoryMcpUserContext.fromAuthentication(
                        SecurityContextHolder.getContext().getAuthentication()))
                .build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStreamableServerTransportProvider> memoryMcpServlet(
            HttpServletStreamableServerTransportProvider provider) {
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
                new ServletRegistrationBean<>(provider, ENDPOINT);
        registration.setName("memoryMcpServlet");
        registration.setLoadOnStartup(1);
        return registration;
    }

    @Bean
    McpSyncServer memoryMcpServer(HttpServletStreamableServerTransportProvider provider,
                                  MemoryMcpToolCatalog catalog) {
        McpSyncServer server = McpServer.sync(provider)
                .serverInfo("xiaochuling-memory", "1.0.0")
                .capabilities(ServerCapabilities.builder().tools(false).build())
                .build();
        catalog.tools().forEach(server::addTool);
        return server;
    }
}
