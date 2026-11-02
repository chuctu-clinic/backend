package vn.chuctu.clinic.common;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Giao dịch luôn mở mới, làm lại khi MySQL báo deadlock (mã 1213), dùng chung cho AppointmentService, QueueService và
 * RecordService. Không gọi từ trong giao dịch khác: giao dịch mới không thấy thay đổi chưa commit của nơi gọi, và vòng
 * làm lại chỉ đúng khi bắt đầu từ một giao dịch sạch.
 */
public final class Tx {

    public static final int MYSQL_DEADLOCK = 1213;
    public static final int MAX_ATTEMPTS = 3;

    private final TransactionTemplate template;

    public Tx(PlatformTransactionManager transactionManager) {
        this.template = new TransactionTemplate(transactionManager);
        this.template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Chạy trong một giao dịch mới, làm lại (tối đa {@value #MAX_ATTEMPTS} lần) khi gặp deadlock. */
    public <T> T run(Supplier<T> work) {
        for (int attempt = 1;; attempt++) {
            try {
                return template.execute(status -> work.get());
            } catch (RuntimeException e) {
                if (attempt >= MAX_ATTEMPTS || !isDeadlock(e)) throw e;
            }
        }
    }

    /** Một giao dịch mới, một lần, không tự làm lại; nơi gọi tự xử lý lỗi (vòng thử riêng) và có thể đặt rollbackOnly. */
    public <T> T once(TransactionCallback<T> work) {
        return template.execute(work);
    }

    /** Lỗi do MySQL báo deadlock (mã 1213) ở bất kỳ tầng nguyên nhân nào. */
    public static boolean isDeadlock(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SQLException sql && sql.getErrorCode() == MYSQL_DEADLOCK) return true;
        }
        return false;
    }

    /** Thời điểm thành giờ UTC cắt về mili giây để ghi vào cột DATETIME(3), không phụ thuộc múi giờ của JVM. */
    public static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }
}
