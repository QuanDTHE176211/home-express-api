package com.homeexpress.home_express_api.controller;

import com.homeexpress.home_express_api.dto.request.*;
import com.homeexpress.home_express_api.dto.response.AuthResponse;
import com.homeexpress.home_express_api.dto.response.UserSummaryResponse;
import com.homeexpress.home_express_api.service.AuthService;
import com.homeexpress.home_express_api.service.OtpService;
import com.homeexpress.home_express_api.service.UserService;
import com.homeexpress.home_express_api.util.AuthenticationUtils;
import com.homeexpress.home_express_api.repository.UserRepository;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseCookie;

import java.util.Map;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final OtpService otpService;
    private final UserService userService;
    private final UserRepository userRepository;

    // Endpoint: Dang ky user moi
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // Endpoint: Dang nhap
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    // Endpoint: Get current user info (for session restoration)
    @GetMapping("/me")
    public ResponseEntity<UserSummaryResponse> me(Authentication authentication) {
        com.homeexpress.home_express_api.entity.User user = AuthenticationUtils.getUser(authentication, userRepository);
        UserSummaryResponse response = userService.getUserSummary(user.getUserId());
        return ResponseEntity.ok(response);
    }

    // Endpoint: Verify Registration OTP and Login
    @PostMapping("/verify-registration")
    public ResponseEntity<AuthResponse> verifyRegistration(@RequestBody VerifyOtpRequest request) {
        AuthResponse response = authService.verifyRegistration(request.getEmail(), request.getCode());
        return ResponseEntity.ok(response);
    }

    // Endpoint: Resend Verification OTP
    @PostMapping("/resend-verification-otp")
    public ResponseEntity<?> resendVerificationOtp(@RequestBody Map<String, String> request) {
        String email = request.get("email");
        if (email == null || email.isEmpty()) {
            throw new RuntimeException("Email is required");
        }
        otpService.createAndSendOtp(email);
        return ResponseEntity.ok(Map.of("message", "OTP sent to your email"));
    }

    // Endpoint: Yeu cau OTP reset password
    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@RequestBody ForgotPasswordRequest request) {
        otpService.createAndSendOtp(request.getEmail());
        return ResponseEntity.ok(Map.of("message", "OTP sent to your email"));
    }

    // Endpoint: Verify OTP
    @PostMapping("/verify-otp")
    public ResponseEntity<?> verifyOtp(@RequestBody VerifyOtpRequest request) {
        boolean isValid = otpService.validateOtp(request.getEmail(), request.getCode());
        return ResponseEntity.ok(Map.of(
                "message", "OTP verified successfully",
                "verified", String.valueOf(isValid)
        ));
    }

    // Endpoint: Reset password
    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        // Verify and consume OTP
        otpService.verifyOtp(request.getEmail(), request.getOtpCode());
        
        authService.resetPassword(request.getEmail(), request.getNewPassword());
        return ResponseEntity.ok(Map.of("message", "Password reset successfully"));
    }

    // Test endpoint
    @GetMapping("/test")
    public ResponseEntity<String> test() {
        return ResponseEntity.ok("Auth API is working!");
    }

    // Endpoint: Logout - clear auth cookies (stateless JWT)
    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletResponse response) {
        // Clear access and refresh token cookies (if present)
        ResponseCookie clearAccess = ResponseCookie.from("access_token", "")
                .path("/")
                .httpOnly(true)
                .secure(false)
                .maxAge(0)
                .sameSite("Lax")
                .build();

        ResponseCookie clearRefresh = ResponseCookie.from("refresh_token", "")
                .path("/")
                .httpOnly(true)
                .secure(false)
                .maxAge(0)
                .sameSite("Lax")
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, clearAccess.toString());
        response.addHeader(HttpHeaders.SET_COOKIE, clearRefresh.toString());

        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }
}
