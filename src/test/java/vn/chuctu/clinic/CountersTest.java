package vn.chuctu.clinic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.chuctu.clinic.common.Counters;

class CountersTest extends IntegrationTest {

    @Autowired Counters counters;

    @Test
    @DisplayName("[java] mỗi khoá đếm riêng, bắt đầu từ 1")
    void dem_rieng_tung_khoa() {
        assertEquals(1, counters.next("ticket-2026-11-02"));
        assertEquals(2, counters.next("ticket-2026-11-02"));
        assertEquals(1, counters.next("patient"));
        assertEquals(3, counters.next("ticket-2026-11-02"));
    }

    @Test
    @DisplayName("[java] 20 luồng lấy số không trùng")
    void hai_muoi_luong() throws Exception {
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Long>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<Long> task = () -> {
                    ready.countDown();
                    go.await();
                    return counters.next("x");
                };
                futures.add(pool.submit(task));
            }
            ready.await();
            go.countDown();
            List<Long> got = new ArrayList<>();
            for (Future<Long> f : futures) got.add(f.get());
            assertEquals(threads, got.size());
            assertEquals(LongStream.rangeClosed(1, threads).boxed().collect(Collectors.toSet()), Set.copyOf(got));
        } finally {
            pool.shutdownNow();
        }
    }
}
