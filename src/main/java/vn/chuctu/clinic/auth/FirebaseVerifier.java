package vn.chuctu.clinic.auth;

import java.time.Instant;

/** Kiểm ID token Firebase: trả số điện thoại (dạng +84...) và thời điểm xác thực, ném lỗi nếu token sai hoặc hết hạn. */
public interface FirebaseVerifier {

    record FirebaseInfo(String phone, Instant authTime) {}

    FirebaseInfo verify(String idToken);
}
