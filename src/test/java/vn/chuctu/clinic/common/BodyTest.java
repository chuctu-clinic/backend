package vn.chuctu.clinic.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class BodyTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static Body body(String json) {
        return Body.of(MAPPER.readTree(json));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> fieldsOf(Body body) {
        ApiException e = (ApiException) catchThrowable(body);
        assertThat(e.code()).isEqualTo("VALIDATION");
        assertThat(e.status()).isEqualTo(400);
        return (Map<String, String>) ((Map<String, Object>) e.toBody().get("error")).get("fields");
    }

    private static Throwable catchThrowable(Body body) {
        try {
            body.check();
        } catch (ApiException e) {
            return e;
        }
        throw new AssertionError("Mong đợi VALIDATION");
    }

    @Test
    @DisplayName("[java] ô bắt buộc: thiếu, sai kiểu, rỗng, đúng")
    void requiredString() {
        Body missing = body("{}");
        missing.requiredString("fullName", Body.Rule.FULL_NAME);
        assertThat(fieldsOf(missing)).containsEntry("fullName", "Cô chú vui lòng nhập họ và tên.");

        Body wrongType = body("{\"fullName\":null}");
        wrongType.requiredString("fullName", Body.Rule.FULL_NAME);
        assertThat(fieldsOf(wrongType)).containsEntry("fullName", "Expected string, received null");

        Body blank = body("{\"fullName\":\"  a \"}");
        blank.requiredString("fullName", Body.Rule.FULL_NAME);
        assertThat(fieldsOf(blank)).containsEntry("fullName", "Cô chú vui lòng nhập họ và tên.");

        Body tooLong = body("{\"fullName\":\"" + "a".repeat(101) + "\"}");
        tooLong.requiredString("fullName", Body.Rule.FULL_NAME);
        assertThat(fieldsOf(tooLong)).containsEntry("fullName", "Họ và tên quá dài.");

        Body ok = body("{\"fullName\":\"  Nguyễn Văn A \",\"phone\":\"+84 912 345 678\",\"lạ\":1}");
        assertThat(ok.requiredString("fullName", Body.Rule.FULL_NAME)).isEqualTo("Nguyễn Văn A");
        assertThat(ok.requiredString("phone", Body.Rule.PHONE)).isEqualTo("0912345678");
        ok.check();
    }

    @Test
    @DisplayName("[java] số điện thoại báo lý do như phoneProblem và mỗi ô chỉ giữ lỗi đầu")
    void phoneProblem() {
        Body b = body("{\"phone\":\"09123\"}");
        b.requiredString("phone", Body.Rule.PHONE);
        b.fail("phone", "lỗi thứ hai");
        assertThat(fieldsOf(b)).containsEntry("phone", "Số điện thoại còn thiếu 5 chữ số. Số di động gồm 10 chữ số.").hasSize(1);
    }

    @Test
    @DisplayName("[java] optionalText có ba trạng thái")
    void optionalText() {
        Body b = body("{\"a\":\"  hi \",\"b\":\"  \",\"c\":null}");
        assertThat(b.optionalText("a", 10).get()).isEqualTo("hi");
        assertThat(b.optionalText("b", 10)).matches(o -> o.isPresent() && o.get() == null);
        assertThat(b.optionalText("c", 10)).matches(o -> o.isPresent() && o.get() == null);
        assertThat(b.optionalText("d", 10).isAbsent()).isTrue();
        b.check();

        Body bad = body("{\"a\":\"abcdef\",\"n\":5}");
        bad.optionalText("a", 3);
        bad.optionalText("n", 3);
        assertThat(fieldsOf(bad)).containsEntry("a", "Tối đa 3 ký tự.").containsEntry("n", "Expected string, received number");
    }

    @Test
    @DisplayName("[java] date và id")
    void dateAndId() {
        Body ok = body("{\"d\":\"2026-02-28\",\"id\":\"12\",\"nul\":null}");
        assertThat(ok.date("d").get()).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(ok.id("id").get()).isEqualTo(12L);
        assertThat(ok.id("nul").isPresent()).isTrue();
        assertThat(ok.id("missing").isAbsent()).isTrue();
        ok.check();

        Body bad = body("{\"d\":\"2026-02-30\",\"e\":\"28/02/2026\",\"a\":\"0\",\"b\":\"99999999999999999999\",\"c\":\"abc\",\"n\":7}");
        bad.date("d");
        bad.date("e");
        bad.id("a");
        bad.id("b");
        bad.id("c");
        bad.id("n");
        assertThat(fieldsOf(bad)).containsEntry("d", "Ngày không hợp lệ.").containsEntry("e", "Ngày không hợp lệ.")
                .containsEntry("a", "Mã không hợp lệ.").containsEntry("b", "Mã không hợp lệ.").containsEntry("c", "Mã không hợp lệ.")
                .containsEntry("n", "Expected string, received number");
    }

    @Test
    @DisplayName("[java] body không phải object báo lỗi gốc _")
    void nonObject() {
        for (String json : new String[] {"[]", "\"x\"", "5", "null"}) {
            Body b = body(json);
            assertThat(b.requiredString("x", Body.Rule.FULL_NAME)).isNull();
            assertThat(fieldsOf(b)).containsOnlyKeys("_");
        }
        Body.of(null).check();
    }

    @Test
    @DisplayName("[java] Paging giống paging() của Node")
    void paging() {
        assertThat(Paging.of(null, null, 20)).isEqualTo(new Paging(1, 20, 0));
        assertThat(Paging.of("3", "10", 20)).isEqualTo(new Paging(3, 10, 20));
        assertThat(Paging.of("0", "0", 20)).isEqualTo(new Paging(1, 20, 0));
        assertThat(Paging.of("-4", "-1", 20)).isEqualTo(new Paging(1, 1, 0));
        assertThat(Paging.of("2abc", "500", 20)).isEqualTo(new Paging(2, 100, 100));
        assertThat(Paging.of("abc", " 7 ", 20)).isEqualTo(new Paging(1, 7, 0));
    }

    @Test
    @DisplayName("[java] Likes escape ký tự đặc biệt")
    void likes() {
        assertThat(Likes.contains("abc")).isEqualTo("%abc%");
        assertThat(Likes.contains("50%_a\\b")).isEqualTo("%50\\%\\_a\\\\b%");
    }

    @Test
    @DisplayName("[java] ngày bắt buộc: thiếu, null, sai kiểu, không có thật, đúng")
    void requiredDate() {
        for (String json : new String[] {"{}", "{\"dob\":null}", "{\"dob\":5}", "{\"dob\":\"2026-02-30\"}", "{\"dob\":\"1/2/1960\"}"}) {
            Body b = body(json);
            assertThat(b.requiredDate("dob")).isNull();
            assertThat(fieldsOf(b)).containsKey("dob");
        }
        Body missing = body("{}");
        missing.requiredDate("dob");
        assertThat(fieldsOf(missing)).containsEntry("dob", "Required");
        Body nul = body("{\"dob\":null}");
        nul.requiredDate("dob");
        assertThat(fieldsOf(nul)).containsEntry("dob", "Expected string, received null");
        Body fake = body("{\"dob\":\"2026-02-30\"}");
        fake.requiredDate("dob");
        assertThat(fieldsOf(fake)).containsEntry("dob", "Ngày không hợp lệ.");
        Body ok = body("{\"dob\":\"1960-01-01\"}");
        assertThat(ok.requiredDate("dob")).isEqualTo(LocalDate.of(1960, 1, 1));
        ok.check();
    }

    @Test
    @DisplayName("[java] enum bắt buộc: thông báo riêng hoặc mặc định của zod")
    void requiredEnum() {
        var values = java.util.List.of("REGISTER", "NEW_DEVICE");
        Body custom = body("{\"purpose\":\"X\"}");
        assertThat(custom.requiredEnum("purpose", values, "Chọn đi")).isNull();
        assertThat(fieldsOf(custom)).containsEntry("purpose", "Chọn đi");

        Body missing = body("{}");
        missing.requiredEnum("purpose", values, null);
        assertThat(fieldsOf(missing)).containsEntry("purpose", "Required");
        Body wrongType = body("{\"purpose\":7}");
        wrongType.requiredEnum("purpose", values, null);
        assertThat(fieldsOf(wrongType)).containsEntry("purpose", "Expected 'REGISTER' | 'NEW_DEVICE', received number");
        Body unknown = body("{\"purpose\":\"X\"}");
        unknown.requiredEnum("purpose", values, null);
        assertThat(fieldsOf(unknown)).containsEntry("purpose", "Invalid enum value. Expected 'REGISTER' | 'NEW_DEVICE', received 'X'");

        Body ok = body("{\"purpose\":\"NEW_DEVICE\"}");
        assertThat(ok.requiredEnum("purpose", values, null)).isEqualTo("NEW_DEVICE");
        ok.check();
    }

    @Test
    @DisplayName("[java] chuỗi tuỳ chọn và boolean mặc định")
    void optionalStringAndBoolean() {
        Body b = body("{\"code\":\"12\",\"idToken\":null,\"remember\":\"yes\"}");
        assertThat(b.optionalString("code", s -> s.length() == 6 ? null : "sai").get()).isEqualTo("12");
        assertThat(b.optionalString("idToken", s -> null).isAbsent()).isTrue();
        assertThat(b.optionalString("khac", s -> "sai").isAbsent()).isTrue();
        assertThat(b.optionalBoolean("remember", true)).isTrue();
        assertThat(fieldsOf(b)).containsEntry("code", "sai").containsEntry("idToken", "Expected string, received null")
                .containsEntry("remember", "Expected boolean, received string");

        Body ok = body("{\"remember\":false}");
        assertThat(ok.optionalBoolean("remember", true)).isFalse();
        assertThat(body("{}").optionalBoolean("remember", true)).isTrue();
    }
}
