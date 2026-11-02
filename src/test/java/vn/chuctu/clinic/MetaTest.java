package vn.chuctu.clinic;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** GET /api/meta: so từng khoá với routes/public.routes.js. Chuỗi mong đợi là response thật của bản Node (SMS_PROVIDER=console). */
class MetaTest extends IntegrationTest {

    /** Response của Node với today thay bằng {TODAY}. */
    private static final String NODE_META = """
            {"today":"{TODAY}","otp":{"provider":"server","firebase":null},"serviceTypes":[{"value":"CHUA_RO","label":"Chưa rõ — nhờ bác sĩ khám và chỉ định"},{"value":"CHAM_CUU","label":"Châm cứu"},{"value":"VAT_LY_TRI_LIEU","label":"Vật lý trị liệu — phục hồi chức năng"},{"value":"XOA_BOP_BAM_HUYET","label":"Xoa bóp — bấm huyệt"},{"value":"BOC_THUOC","label":"Bốc thuốc — thuốc Nam, thuốc Bắc"}],"slots":["08:00","09:00","10:00","11:00","13:00","14:00","15:00","16:00"],"slotCapacity":6,"userMaxDays":30,"counterMaxDays":7,"seats":5,"priorityEarlyMinutes":15,"slotMinutes":60,"maxProfiles":10,"relationships":["BAN_THAN","VO_CHONG","CON","CHA_ME","ONG_BA","ANH_CHI_EM","KHAC"],"statusLabels":{"BOOKED":"Chưa đến","CHECKED_IN":"Đã check-in","IN_PROGRESS":"Đã vào khám","COMPLETED":"Đã hoàn thành","CANCELLED":"Đã huỷ"},"cancelReasons":["Bệnh nhân xin huỷ","Không đến được","Đặt nhầm","Khác"],"postCategories":[{"value":"TIN_TUC","label":"Tin tức"},{"value":"HUONG_DAN","label":"Hướng dẫn"},{"value":"THIEN_NGUYEN","label":"Thiện nguyện"},{"value":"SU_KIEN","label":"Sự kiện"},{"value":"TRANG","label":"Trang thông tin"}]}
            """.strip();

    @Test
    @DisplayName("[java] meta giống Node từng khoá, đúng thứ tự, ngày theo giờ Việt Nam")
    void metaGiongNode() {
        // 20:00 UTC là 03:00 hôm sau theo giờ Việt Nam
        clock.set(Instant.parse("2026-11-02T20:00:00Z"));
        var res = client().get("/api/meta").expectStatus(200);
        assertThat(res.text()).isEqualTo(NODE_META.replace("{TODAY}", "2026-11-03"));
        assertThat(res.headers().firstValue("content-type")).hasValueSatisfying(v -> assertThat(v).startsWith("application/json"));
    }

    @Test
    @DisplayName("[java] meta không cần đăng nhập và là cùng một JSON dù có đăng nhập")
    void metaKhongCanDangNhap() {
        var anonymous = JsonMapper.builder().build().readTree(client().get("/api/meta").expectStatus(200).text());
        var staff = JsonMapper.builder().build().readTree(client().staff().get("/api/meta").expectStatus(200).text());
        assertThat(staff).isEqualTo(anonymous);
    }
}
