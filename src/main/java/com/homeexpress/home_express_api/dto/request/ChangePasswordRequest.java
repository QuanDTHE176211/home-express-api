package com.homeexpress.home_express_api.dto.request;

import com.homeexpress.home_express_api.constants.PasswordValidationConstants;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class ChangePasswordRequest {

    @NotBlank(message = "Mat khau hien tai khong duoc de trong")
    private String oldPassword;

    @NotBlank(message = PasswordValidationConstants.PASSWORD_REQUIRED_MESSAGE)
    @Size(min = PasswordValidationConstants.MIN_LENGTH, message = PasswordValidationConstants.PASSWORD_MIN_LENGTH_MESSAGE)
    @Pattern(regexp = PasswordValidationConstants.PASSWORD_REGEX, message = PasswordValidationConstants.PASSWORD_COMPLEXITY_MESSAGE)
    private String newPassword;

    public ChangePasswordRequest() {
    }

    public String getOldPassword() {
        return oldPassword;
    }

    public void setOldPassword(String oldPassword) {
        this.oldPassword = oldPassword;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }
}
