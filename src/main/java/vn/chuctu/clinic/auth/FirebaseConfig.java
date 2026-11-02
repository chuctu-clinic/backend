package vn.chuctu.clinic.auth;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.chuctu.clinic.common.AppProperties;

@Configuration(proxyBeanMethods = false)
class FirebaseConfig {

    /** Bộ kiểm token thật chỉ có khi SMS_PROVIDER=firebase; thiếu FIREBASE_PROJECT_ID thì từ chối mọi token (như Node). */
    @Bean
    FirebaseVerifier firebaseVerifier(AppProperties config, Clock clock) {
        if (!config.smsProvider.equals("firebase")) return new UnavailableFirebaseVerifier("SMS_PROVIDER không phải firebase.");
        String projectId = config.firebase.projectId();
        if (projectId.isEmpty()) return new UnavailableFirebaseVerifier("Thiếu FIREBASE_PROJECT_ID trong .env.");
        return new NimbusFirebaseVerifier(projectId, NimbusFirebaseVerifier.googleKeys(), NimbusFirebaseVerifier.emulatorAllowed(config), clock);
    }
}
