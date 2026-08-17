package com.novabank.userservice.security;

import static org.springframework.util.StringUtils.hasText;

import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private final JwtService jwtService;
    private final StringRedisTemplate redisTemplate;

    public JwtAuthenticationFilter(JwtService jwtService, StringRedisTemplate redisTemplate) {
        this.jwtService = jwtService;
        this.redisTemplate = redisTemplate;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (!hasText(authorization) || !authorization.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }
        String token = authorization.substring(7);
        try {
            Claims claims = jwtService.verifyAndExtract(token);
            String userId = claims.getSubject();
            String cachedToken = redisTemplate.opsForValue().get(userId);
            if (!token.equals(cachedToken)) {
                request.setAttribute("jwt_error", "SESSION_REVOKED");
                SecurityContextHolder.clearContext();
                filterChain.doFilter(request, response);
                return;
            }
            Authentication authentication = new UsernamePasswordAuthenticationToken(userId, null, List.of());
            SecurityContextHolder.getContext()
                    .setAuthentication(authentication);
        } catch (ExpiredJwtException e) {
            log.debug("Expired JWT token: {}", token);
            request.setAttribute("jwt_error", "EXPIRED");
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Invalid JWT token: {}", token, e);
            request.setAttribute("jwt_error", "INVALID");
            SecurityContextHolder.clearContext();
        }
        filterChain.doFilter(request, response);
    }
}