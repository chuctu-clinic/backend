package vn.chuctu.clinic.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VnTimeTest {

    @Test
    @DisplayName("[java] today tính theo giờ Việt Nam, qua nửa đêm UTC+7 thì sang ngày mới")
    void today() {
        assertEquals(LocalDate.parse("2026-11-02"), VnTime.today(Instant.parse("2026-11-01T17:30:00Z")));
        assertEquals(LocalDate.parse("2026-11-01"), VnTime.today(Instant.parse("2026-11-01T16:59:59Z")));
    }

    @Test
    @DisplayName("[java] slotInstant đổi giờ Việt Nam sang UTC")
    void slotInstant() {
        assertEquals(Instant.parse("2026-11-02T01:00:00Z"), VnTime.slotInstant(LocalDate.parse("2026-11-02"), "08:00"));
        assertEquals(Instant.parse("2026-11-02T09:00:00Z"), VnTime.slotInstant(LocalDate.parse("2026-11-02"), "16:00"));
    }

    @Test
    @DisplayName("[java] ddmm, yy, số phút trong ngày và đổi hh:mm ra phút")
    void formats() {
        assertEquals("0211", VnTime.ddmm(LocalDate.parse("2026-11-02")));
        assertEquals("26", VnTime.yy(LocalDate.parse("2026-11-02")));
        assertEquals(10 * 60 + 5, VnTime.minutesOfDay(Instant.parse("2026-11-02T03:05:00Z")));
        assertEquals(0, VnTime.minutesOfDay(Instant.parse("2026-11-01T17:00:00Z")));
        assertEquals(8 * 60 + 30, VnTime.hhmmToMinutes("08:30"));
    }
}
