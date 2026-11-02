package vn.chuctu.clinic.common;

import java.util.List;

/** Quy tắc nghiệp vụ dùng chung (BR.03, BR.12 đến BR.14, BR.29), giá trị như {@code rules} trong config.js. */
public final class Rules {

    private Rules() {}

    public static final int OTP_LENGTH = 6;
    public static final int OTP_TTL_MINUTES = 5;
    /** Tin nhắn, cho mỗi số điện thoại. */
    public static final int OTP_MAX_PER_HOUR = 3;
    /** Email, cho mỗi địa chỉ email. */
    public static final int OTP_EMAIL_MAX_PER_HOUR = 5;
    public static final int OTP_RESEND_SECONDS = 60;
    public static final int OTP_MAX_ATTEMPTS = 5;

    public static final int MAX_PROFILES_PER_ACCOUNT = 10;

    /** Hôm nay, chỉ các khung chưa bắt đầu. */
    public static final int BOOKING_USER_MIN_DAYS = 0;
    public static final int BOOKING_USER_MAX_DAYS = 30;
    public static final int BOOKING_COUNTER_MAX_DAYS = 7;
    public static final int SLOT_CAPACITY = 6;
    public static final List<String> SLOTS = List.of("08:00", "09:00", "10:00", "11:00", "13:00", "14:00", "15:00", "16:00");
    public static final int SLOT_MINUTES = 60;

    public static final int QUEUE_SEATS = 5;
    /** Đến sớm tối đa bao nhiêu phút trước khung giờ vẫn được tính là "đúng khung giờ". */
    public static final int QUEUE_PRIORITY_EARLY_MINUTES = 15;

    public static final int SESSION_DAYS = 30;

    public static final int MIN_PASSWORD_LENGTH = 8;
}
