package vn.chuctu.clinic.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HmacTest {

    @Test
    @DisplayName("[java] HMAC-SHA256 hex đúng vectơ mẫu RFC 4231 (trường hợp 2)")
    void rfc4231() {
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
                Hmac.sha256Hex("Jefe", "what do ya want for nothing?"));
    }
}
