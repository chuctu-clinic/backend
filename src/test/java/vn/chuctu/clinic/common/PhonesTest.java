package vn.chuctu.clinic.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Chuyển từ backend/test/phone.test.js. Ba test cuối của file Node (phoneDigits, toPhone, phoneInputError) kiểm hàm
 * của ô nhập phía trình duyệt, server không dùng nên chưa chuyển.
 */
class PhonesTest {

    private static void assertMatches(String regex, String actual) {
        assertNotNull(actual);
        assertTrue(Pattern.compile(regex).matcher(actual).find(), actual);
    }

    @Test
    @DisplayName("bỏ khoảng trắng, dấu chấm, gạch và đưa +84, 84 về số 0")
    void stripsAndConverts() {
        assertEquals("0912345678", Phones.normalize("0912 345 678"));
        assertEquals("0912345678", Phones.normalize("0912.345-678"));
        assertEquals("0912345678", Phones.normalize("(0912) 345678"));
        assertEquals("0912345678", Phones.normalize("+84912345678"));
        assertEquals("0912345678", Phones.normalize("84912345678"));
    }

    @Test
    @DisplayName("chỉ đổi đầu 84 khi đủ 11 số, không phải chuỗi thì giữ nguyên")
    void keepsOtherInput() {
        assertEquals("8491234567", Phones.normalize("8491234567"));
        assertNull(Phones.normalize(null));
    }

    @Test
    @DisplayName("nhận số di động 10 chữ số với các đầu số 03, 05, 07, 08, 09")
    void acceptsMobile() {
        for (String phone : List.of("0312345678", "0512345678", "0712345678", "0812345678", "0912345678", "+84912345678", "0912 345 678")) {
            assertNull(Phones.problem(phone, true), phone);
        }
    }

    @Test
    @DisplayName("báo đúng lý do cho từng kiểu sai")
    void explainsEachCase() {
        assertMatches("chỉ gồm chữ số", Phones.problem("09a2345678", true));
        assertMatches("còn thiếu 5 chữ số", Phones.problem("09123", true));
        assertMatches("bị thừa 1 chữ số", Phones.problem("09123456789", true));
        assertMatches("Đầu số 02 không phải đầu số di động", Phones.problem("0212345678", true));
    }

    @Test
    @DisplayName("ô trống chỉ báo lỗi khi bắt buộc")
    void blankOnlyWhenRequired() {
        assertMatches("vui lòng nhập số điện thoại", Phones.problem("", true));
        assertMatches("vui lòng nhập số điện thoại", Phones.problem(null, true));
        assertNull(Phones.problem("", false));
    }
}
