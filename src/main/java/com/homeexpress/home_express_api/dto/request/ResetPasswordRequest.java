package com.homeexpress.home_express_api.dto.request;

import com.homeexpress.home_express_api.constants.PasswordValidationConstants;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class ResetPasswordRequest {
    
    private String email;
    
    @NotBlank(message = PasswordValidationConstants.PASSWORD_REQUIRED_MESSAGE)
    @Size(min = PasswordValidationConstants.MIN_LENGTH, message = PasswordValidationConstants.PASSWORD_MIN_LENGTH_MESSAGE)
    @Pattern(regexp = PasswordValidationConstants.PASSWORD_REGEX, message = PasswordValidationConstants.PASSWORD_COMPLEXITY_MESSAGE)
    private String newPassword;
    
    private String otpCode;

    public ResetPasswordRequest() {}

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }

    public String getOtpCode() {
        return otpCode;
    }

    public void setOtpCode(String otpCode) {
        this.otpCode = otpCode;
    }
}
