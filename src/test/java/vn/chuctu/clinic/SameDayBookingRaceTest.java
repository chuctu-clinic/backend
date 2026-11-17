package vn.chuctu.clinic;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.chuctu.clinic.BookingTest.book;
import static vn.chuctu.clinic.BookingTest.code;
import static vn.chuctu.clinic.BookingTest.futureDate;
import static vn.chuctu.clinic.BookingTest.parallel;
import static vn.chuctu.clinic.TestClient.body;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A1: một hồ sơ chỉ có một phiếu còn hiệu lực mỗi ngày, kể cả khi nhiều yêu cầu đặt đến cùng lúc. Gửi song song qua
 * HTTP như người dùng bấm từ nhiều thẻ, lặp nhiều vòng để bắt được chạy đua.
 */
class SameDayBookingRaceTest extends IntegrationTest {

    private static final List<String> STARTS = List.of("08:00", "09:00", "10:00", "11:00", "13:00");

    private int activeTickets(String patientId, String date) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM appointments WHERE patient_id = ? AND slot_date = ? "
                + "AND status IN ('BOOKED','CHECKED_IN','IN_PROGRESS')", Integer.class, Long.parseLong(patientId), date);
    }

    private int bookedInSlots(String date) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(`count`), 0) FROM slot_counters WHERE slot_date = ?", Integer.class, date);
    }

    @ParameterizedTest(name = "{0} yêu cầu song song")
    @ValueSource(ints = {2, 5})
    @DisplayName("người dùng gửi song song nhiều yêu cầu đặt cùng hồ sơ cùng ngày: 1 yêu cầu 201, còn lại 409 ALREADY_BOOKED")
    void nguoiDungDatSongSong(int n) throws Exception {
        var agent = client().registerUser();
        String patientId = agent.selfPatientId();
        for (int round = 0; round < 9; round++) {
            String date = futureDate(1 + round);
            // Vòng chẵn: cùng một khung; vòng lẻ: mỗi yêu cầu một khung khác
            boolean sameSlot = round % 2 == 0;
            List<TestClient.Result> results = parallel(n, i -> book(agent, patientId, date, sameSlot ? "09:00" : STARTS.get(i)));

            String all = results.stream().map(r -> r.status() + " " + r.text()).toList().toString();
            assertThat(results.stream().filter(r -> r.status() == 201).count()).as(all).isEqualTo(1);
            assertThat(results.stream().filter(r -> r.status() == 409 && code(r).equals("ALREADY_BOOKED")).count()).as(all)
                    .isEqualTo(n - 1);
            assertThat(activeTickets(patientId, date)).isEqualTo(1);
            // Yêu cầu bị từ chối không giữ chỗ trong khung giờ
            assertThat(bookedInSlots(date)).isEqualTo(1);
        }
    }

    @ParameterizedTest(name = "{0} yêu cầu song song")
    @ValueSource(ints = {2, 5})
    @DisplayName("tiếp tân đặt hộ song song cho cùng hồ sơ cùng ngày: 1 yêu cầu 201, còn lại 409 ALREADY_BOOKED")
    void tiepTanDatHoSongSong(int n) throws Exception {
        var staff = client().staff();
        String patientId = staff.post("/api/staff/patients", body("fullName", "Bệnh nhân Quầy", "birthYear", 1960, "phone", "0916000001"))
                .expectStatus(201).json().get("patient").get("id").stringValue();
        for (int round = 0; round < 9; round++) {
            // Tiếp tân chỉ đặt trong 7 ngày tới: vòng 8, 9 huỷ phiếu cũ rồi đặt lại ngày đã dùng
            String date = futureDate(1 + round % 7);
            if (round >= 7) jdbc.update("UPDATE appointments SET status = 'CANCELLED' WHERE slot_date = ?", date);
            if (round >= 7) jdbc.update("DELETE FROM slot_counters WHERE slot_date = ?", date);
            List<TestClient.Result> results = parallel(n, i -> staff.post("/api/staff/appointments",
                    body("patientId", patientId, "serviceType", "CHAM_CUU", "slotDate", date, "slotStart", STARTS.get(i))));

            String all = results.stream().map(r -> r.status() + " " + r.text()).toList().toString();
            assertThat(results.stream().filter(r -> r.status() == 201).count()).as(all).isEqualTo(1);
            assertThat(results.stream().filter(r -> r.status() == 409 && code(r).equals("ALREADY_BOOKED")).count()).as(all)
                    .isEqualTo(n - 1);
            assertThat(activeTickets(patientId, date)).isEqualTo(1);
            assertThat(bookedInSlots(date)).isEqualTo(1);
        }
    }
}
