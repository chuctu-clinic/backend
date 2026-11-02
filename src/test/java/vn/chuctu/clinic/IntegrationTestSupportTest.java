package vn.chuctu.clinic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.chuctu.clinic.common.BusinessClock;
import vn.chuctu.clinic.common.VnTime;

class IntegrationTestSupportTest extends IntegrationTest {

    @Autowired BusinessClock injectedBusinessClock;
    @Autowired Clock injectedClock;

    @Test
    @DisplayName("[java] đang nối đúng database chuctu_test")
    void dung_database_kiem_thu() {
        assertEquals(TestDatabase.NAME, jdbc.queryForObject("SELECT DATABASE()", String.class));
    }

    @Test
    @DisplayName("[java] đồng hồ nghiệp vụ mặc định 10:00 hôm nay giờ Việt Nam, đổi được và đặt lại được")
    void dong_ho_nghiep_vu() {
        assertEquals(10 * 60, VnTime.minutesOfDay(injectedBusinessClock.now()));
        assertEquals(VnTime.today(Instant.now()), VnTime.today(injectedBusinessClock.now()));

        Instant fixed = Instant.parse("2026-11-02T09:45:00Z");
        businessClock.set(fixed);
        assertEquals(fixed, injectedBusinessClock.now());
        businessClock.reset();
        assertEquals(10 * 60, VnTime.minutesOfDay(injectedBusinessClock.now()));
    }

    @Test
    @DisplayName("[java] bean Clock đổi được, đặt lại về giờ hệ thống cắt mili giây")
    void dong_ho_he_thong() {
        Instant fixed = Instant.parse("2026-11-02T03:00:00Z");
        clock.set(fixed);
        assertEquals(fixed, injectedClock.instant());
        clock.reset();
        Instant now = injectedClock.instant();
        assertEquals(now.truncatedTo(ChronoUnit.MILLIS), now);
    }
}
