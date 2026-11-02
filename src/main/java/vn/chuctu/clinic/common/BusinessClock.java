package vn.chuctu.clinic.common;

import java.time.Instant;

/**
 * Tương đương {@code clock.now} của bản Node. Chỉ dùng ở bốn chỗ Node dùng clock.now: autoCancelNoShows,
 * isReceivingTime, assertReceivingTime, checkIn. Nơi khác dùng bean {@link java.time.Clock}.
 */
@FunctionalInterface
public interface BusinessClock {

    Instant now();
}
