package vn.chuctu.clinic.auth;

/** Dùng khi chưa kiểm được token Firebase (SMS_PROVIDER không phải firebase, hoặc thiếu FIREBASE_PROJECT_ID): luôn từ chối. */
final class UnavailableFirebaseVerifier implements FirebaseVerifier {

    private final String reason;

    UnavailableFirebaseVerifier(String reason) {
        this.reason = reason;
    }

    @Override
    public FirebaseInfo verify(String idToken) {
        throw new IllegalStateException(reason);
    }
}
