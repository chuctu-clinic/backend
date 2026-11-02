package vn.chuctu.clinic.auth;

import org.springframework.stereotype.Component;

/** Bản tạm cho tới Task 5 (NimbusFirebaseVerifier): chưa kiểm được token nên luôn từ chối. */
@Component
class UnavailableFirebaseVerifier implements FirebaseVerifier {

    @Override
    public FirebaseInfo verify(String idToken) {
        throw new IllegalStateException("Chưa có bộ kiểm token Firebase (Task 5)");
    }
}
