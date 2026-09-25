package com.example.food.security;

import com.example.food.common.ApiResponse;
import com.example.food.user.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Configuration
public class SecurityConfig {

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    public SecurityConfig(JwtService jwtService, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AuthenticationEntryPoint authenticationEntryPoint,
            AccessDeniedHandler accessDeniedHandler,
            ObjectProvider<UserMapper> userMapperProvider
    ) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/auth/user/register/code").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/user/register").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/user/code").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/user/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/user/password-login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/user/password/reset/code").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/user/password/reset").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/admin/login").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/metrics/**").hasRole("ADMIN")
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/feature-suggestions/**").hasRole("USER")
                        .requestMatchers("/api/user/**").hasRole("USER")
                        .requestMatchers("/api/users/me/**").hasRole("USER")
                        .requestMatchers("/api/recipes/saved/**").hasRole("USER")
                        .requestMatchers("/api/finished-dish-reviews/**").hasRole("USER")
                        .requestMatchers("/api/recommendation-feedbacks/**").hasRole("USER")
                        .requestMatchers("/api/videos/**").hasRole("USER")
                        .requestMatchers("/api/agent/**").hasRole("USER")
                        .requestMatchers("/api/memory/**").hasRole("USER")
                        .requestMatchers("/api/shared/recipes/**").permitAll()
                        .requestMatchers("/api/ai/**").permitAll()
                        .requestMatchers("/api/stats/hot-ingredients", "/api/stats/hot-recipes").permitAll()
                        .requestMatchers("/api/ingredients/images/**").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(
                        new JwtAuthenticationFilter(
                                jwtService,
                                authenticationEntryPoint,
                                userMapperProvider.getIfAvailable()
                        ),
                        UsernamePasswordAuthenticationFilter.class
                )
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) ->
                writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
    }

    @Bean
    AccessDeniedHandler accessDeniedHandler() {
        return (request, response, accessDeniedException) ->
                writeError(response, HttpServletResponse.SC_FORBIDDEN, "Forbidden");
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(status, message));
    }
}
