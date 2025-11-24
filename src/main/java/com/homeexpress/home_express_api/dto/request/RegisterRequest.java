package com.homeexpress.home_express_api.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.homeexpress.home_express_api.constants.PasswordValidationConstants;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * Registration payload for public sign up. Added optional district/ward/taxCode
 * so the transport form can submit
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RegisterRequest {

    private String username;
    private String email;
    private String phone;

    @NotBlank(message = PasswordValidationConstants.PASSWORD_REQUIRED_MESSAGE)
    @Size(min = PasswordValidationConstants.MIN_LENGTH, message = PasswordValidationConstants.PASSWORD_MIN_LENGTH_MESSAGE)
    @Pattern(regexp = PasswordValidationConstants.PASSWORD_REGEX, message = PasswordValidationConstants.PASSWORD_COMPLEXITY_MESSAGE)
    private String password;

    private String role; // "customer", "transport", "manager"
    private String fullName;
    private String address;
    private String city;
    private String district;
    private String ward;
    private String companyName;
    private String businessLicenseNumber;
    private String taxCode;

}
