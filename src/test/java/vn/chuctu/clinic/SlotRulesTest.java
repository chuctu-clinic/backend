package vn.chuctu.clinic;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.chuctu.clinic.appointment.SlotService;
import vn.chuctu.clinic.common.VnTime;

/** Cổng từ test/slot-rules.test.js: quy tắc chọn khung giờ, thời điểm "bây giờ" truyền vào từng lần gọi. */
class SlotRulesTest extends IntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    @Autowired SlotService slots;

    private static Instant at(String hhmm) {
        return VnTime.slotInstant(TODAY, hhmm);
    }

    private String issue(LocalDate date, String start, String mode, Instant now) {
        return slots.timeIssue(date, start, mode, now);
    }

    @Test
    @DisplayName("người dùng không chọn được khung đã bắt đầu trong hôm nay")
    void nguoiDungKhongChonKhungDaBatDau() {
        Instant now = at("10:00");
        assertThat(issue(TODAY, "09:00", "USER", now)).isEqualTo("Khung giờ đã bắt đầu.");
        assertThat(issue(TODAY, "10:00", "USER", now)).isEqualTo("Khung giờ đã bắt đầu.");
        assertThat(issue(TODAY, "11:00", "USER", now)).isNull();
        assertThat(issue(TODAY, "10:00", "USER", at("09:59"))).isNull();
    }

    @Test
    @DisplayName("tiếp tân đặt hộ được khung đang diễn ra, không đặt được khung đã kết thúc")
    void tiepTanDatHoKhungDangDienRa() {
        assertThat(issue(TODAY, "10:00", "COUNTER", at("10:30"))).isNull();
        assertThat(issue(TODAY, "09:00", "COUNTER", at("09:59"))).isNull();
        assertThat(issue(TODAY, "09:00", "COUNTER", at("10:00"))).isEqualTo("Khung giờ đã kết thúc.");
    }

    @Test
    @DisplayName("người dùng đặt trước tối đa 30 ngày, tiếp tân tối đa 7 ngày")
    void toiDaSoNgayDatTruoc() {
        Instant now = at("10:00");
        assertThat(issue(TODAY.plusDays(30), "08:00", "USER", now)).isNull();
        assertThat(issue(TODAY.plusDays(31), "08:00", "USER", now)).contains("30 ngày");
        assertThat(issue(TODAY.plusDays(7), "08:00", "COUNTER", now)).isNull();
        assertThat(issue(TODAY.plusDays(8), "08:00", "COUNTER", now)).contains("7 ngày");
    }

    @Test
    @DisplayName("không nhận ngày đã qua và khung giờ ngoài danh sách")
    void khongNhanNgayDaQuaVaKhungNgoaiDanhSach() {
        Instant now = at("10:00");
        assertThat(issue(TODAY.minusDays(1), "08:00", "USER", now)).isEqualTo("Ngày khám đã qua.");
        assertThat(issue(TODAY.minusDays(1), "08:00", "COUNTER", now)).isEqualTo("Ngày khám đã qua.");
        assertThat(issue(TODAY.plusDays(1), "12:00", "USER", now)).isEqualTo("Khung giờ không hợp lệ.");
    }

    @Test
    @DisplayName("[java] thông báo quá hạn ghi đúng số ngày; availability ghi lý do đã bắt đầu, đã đủ chỗ")
    void javaThongBaoVaKhaDung() {
        Instant now = at("10:00");
        assertThat(issue(TODAY.plusDays(31), "08:00", "USER", now)).isEqualTo("Chỉ đặt được lịch trong vòng 30 ngày tới.");
        assertThat(issue(TODAY.plusDays(8), "08:00", "COUNTER", now)).isEqualTo("Chỉ đặt được lịch trong vòng 7 ngày tới.");

        for (int i = 0; i < 6; i++) assertThat(slots.reserve(TODAY, "14:00")).isTrue();
        var list = slots.availability(TODAY, "USER", now);
        assertThat(list).hasSize(8);
        var nine = list.get(1);
        assertThat(nine.get("start")).isEqualTo("09:00");
        assertThat(nine.get("end")).isEqualTo("10:00");
        assertThat(nine.get("available")).isEqualTo(false);
        assertThat(nine.get("reason")).isEqualTo("Đã bắt đầu");
        var full = list.get(5);
        assertThat(full.get("start")).isEqualTo("14:00");
        assertThat(full.get("booked")).isEqualTo(6);
        assertThat(full.get("remaining")).isEqualTo(0);
        assertThat(full.get("capacity")).isEqualTo(6);
        assertThat(full.get("reason")).isEqualTo("Đã đủ chỗ");
        assertThat(list.get(4).get("available")).isEqualTo(true); // 13:00
        assertThat(list.get(4).get("reason")).isNull();
        // Chỗ thứ 7 bị từ chối, nhả chỗ rồi giữ lại được
        assertThat(slots.reserve(TODAY, "14:00")).isFalse();
        slots.release(TODAY, "14:00");
        assertThat(slots.reserve(TODAY, "14:00")).isTrue();
        // Nhả nhiều lần không âm
        for (int i = 0; i < 8; i++) slots.release(TODAY, "14:00");
        assertThat(slots.availability(TODAY, "USER", now).get(5).get("booked")).isEqualTo(0);
    }
}
