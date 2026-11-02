package vn.chuctu.clinic;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TestDatabaseTest {

    @Test
    @DisplayName("[java] URL kiểm thử đổi tên database thành chuctu_test, giữ nguyên tham số")
    void swapsDatabaseName() {
        assertEquals("jdbc:mysql://localhost:3306/chuctu_test", TestDatabase.testUrl("jdbc:mysql://localhost:3306/chuctu"));
        assertEquals(
                "jdbc:mysql://127.0.0.1/chuctu_test?useSSL=false&allowPublicKeyRetrieval=true",
                TestDatabase.testUrl("jdbc:mysql://127.0.0.1/chuctu?useSSL=false&allowPublicKeyRetrieval=true"));
        assertEquals("jdbc:mysql://localhost:3306/chuctu_test", TestDatabase.testUrl("jdbc:mysql://localhost:3306/chuctu_demo"));
    }

    @Test
    @DisplayName("[java] URL không đúng dạng jdbc:mysql thì từ chối")
    void rejectsOtherUrls() {
        assertThrows(IllegalStateException.class, () -> TestDatabase.testUrl("jdbc:mariadb://localhost/chuctu"));
        assertThrows(IllegalStateException.class, () -> TestDatabase.testUrl("jdbc:mysql://localhost:3306"));
        assertThrows(IllegalStateException.class, () -> TestDatabase.testUrl(null));
    }

    @Test
    @DisplayName("[java] chỉ cho xoá dữ liệu khi database hiện tại đúng là chuctu_test")
    void wipeGuard() {
        assertDoesNotThrow(() -> TestDatabase.assertSafeToWipe("chuctu_test"));
        for (String name : new String[] {"chuctu", "chuctu_demo", "chuctu_test2", "CHUCTU", "", null}) {
            assertThrows(IllegalStateException.class, () -> TestDatabase.assertSafeToWipe(name), String.valueOf(name));
        }
    }
}
