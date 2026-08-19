package com.novabank.userservice.security;

import java.time.Duration;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;

@Service
public class JwtService {

    private final SecretKey secretKey;
    private final StringRedisTemplate redisTemplate;
    private final long jwtExpiry;

    public JwtService(SecretKey secretKey, StringRedisTemplate redisTemplate, @Value("${jwt.expiry}") long jwtExpiry) {
        this.secretKey = secretKey;
        this.redisTemplate = redisTemplate;
        this.jwtExpiry = jwtExpiry;
    }

    public String generateToken(UUID userId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + jwtExpiry);

        return Jwts.builder()
                .subject(userId.toString())
                .issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey)
                .compact();
    }

    public Claims verifyAndExtract(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public void storeToken(UUID userId, String token) {
        redisTemplate.opsForValue().set(userId.toString(), token, Duration.ofMillis(jwtExpiry));
    }
}
