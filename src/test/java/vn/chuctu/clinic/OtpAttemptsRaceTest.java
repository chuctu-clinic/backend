package vn.chuctu.clinic;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.chuctu.clinic.TestClient.body;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.chuctu.clinic.common.Rules;

/** Giới hạn số lần nhập sai mã phải đúng cả khi kẻ dò mã gửi nhiều yêu cầu cùng lúc. */
class OtpAttemptsRaceTest extends IntegrationTest {

    @Test
    @DisplayName("[java] nhiều lần nhập sai cùng lúc: tối đa OTP_MAX_ATTEMPTS lần được so mã")
    void nhapSaiDongThoi() throws Exception {
        String phone = "0917000001";
        client().post("/api/auth/register", body("fullName", "Dò Mã", "phone", phone, "dob", "1960-01-01", "gender", "NAM",
                "address", "Bình Thạnh")).expectStatus(200);
        int n = 20;
        List<TestClient.Result> results = BookingTest.parallel(n, i -> client().post("/api/auth/register/verify",
                body("phone", phone, "code", String.format("%06d", 900000 + i))));

        long compared = results.stream().filter(r -> r.status() == 400 && BookingTest.code(r).equals("CODE_WRONG")).count();
        long locked = results.stream().filter(r -> r.status() == 400 && BookingTest.code(r).equals("CODE_LOCKED")).count();
        assertThat(compared).as(results.stream().map(TestClient.Result::text).toList().toString()).isLessThanOrEqualTo(Rules.OTP_MAX_ATTEMPTS);
        assertThat(compared + locked).isEqualTo(n);
        assertThat(jdbc.queryForObject("SELECT attempts FROM verification_codes WHERE phone = ?", Integer.class, phone))
                .isLessThanOrEqualTo(Rules.OTP_MAX_ATTEMPTS);
    }
}
