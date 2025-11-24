package com.homeexpress.home_express_api.constants;

public final class PasswordValidationConstants {

    private PasswordValidationConstants() {
    }

    public static final int MIN_LENGTH = 10;

    public static final String PASSWORD_REGEX =
            "^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=.*[!@#$%^&*()_+\\-=\\[\\]{}|;:,.<>?]).{" + MIN_LENGTH + ",}$";

    public static final String PASSWORD_REQUIRED_MESSAGE = "Mat khau khong duoc de trong";
    public static final String PASSWORD_MIN_LENGTH_MESSAGE = "Mat khau phai co it nhat " + MIN_LENGTH + " ky tu";
    public static final String PASSWORD_COMPLEXITY_MESSAGE = "Mat khau phai co it nhat " + MIN_LENGTH
            + " ky tu, bao gom chu hoa, chu thuong, so va ky tu dac biet";
}
