package com.novabank.userservice.security;

import java.io.IOException;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        String jwtError = request.getAttribute("jwt_error") != null ? (String) request.getAttribute("jwt_error") : "";
        String message = switch (jwtError) {
            case "EXPIRED" -> "Token has expired";
            case "INVALID" -> "Invalid JWT token";
            default -> "Unauthorized";
        };
        response.getWriter().write(message);
    }
}
