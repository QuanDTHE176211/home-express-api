package com.homeexpress.home_express_api.config;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

@Component
public class JwtTokenProvider {

    // Secret key de ky JWT (nen luu trong environment variable)
    private final SecretKey secretKey = Jwts.SIG.HS512.key().build();

    // Thoi gian het han token: 24 gio
    private final long jwtExpirationMs = 86400000L; // 24 hours
    private final long refreshTokenExpirationMs = 604800000L; // 7 days

    // Tao JWT token
    public String generateToken(Long userId, String email, String role) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtExpirationMs);

        return Jwts.builder()
                .subject(userId.toString()) // User ID
                .claim("email", email)
                .claim("role", role)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(secretKey, Jwts.SIG.HS512)
                .compact();
    }

    // Lay user ID tu token
    public Long getUserIdFromToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        return Long.parseLong(claims.getSubject());
    }

    // Validate token
    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    // Generate access token
    public String generateAccessToken(Long userId, String email, String role) {
        return generateToken(userId, email, role);
    }

    // Generate refresh token
    public String generateRefreshToken(Long userId) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + refreshTokenExpirationMs);

        return Jwts.builder()
                .subject(userId.toString())
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(secretKey, Jwts.SIG.HS512)
                .compact();
    }

    // Get refresh token expiration
    public long getRefreshTokenExpiration() {
        return refreshTokenExpirationMs;
    }

    // Get role from token
    public String getRoleFromToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        return claims.get("role", String.class);
    }

    // Get token type
    public String getTokenType(String token) {
        return "Bearer";
    }
}
