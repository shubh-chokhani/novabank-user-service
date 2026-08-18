package com.novabank.userservice.security;

import java.time.Duration;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;

@Service
public class JwtService {

    private final SecretKey secretKey;
    private final StringRedisTemplate redisTemplate;

    public JwtService(SecretKey secretKey, StringRedisTemplate redisTemplate) {
        this.secretKey = secretKey;
        this.redisTemplate = redisTemplate;
    }

    public String generateToken(UUID userId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + 10 * 60 * 1000);

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
        redisTemplate.opsForValue().set(userId.toString(), token, Duration.ofMinutes(10));
    }
}
