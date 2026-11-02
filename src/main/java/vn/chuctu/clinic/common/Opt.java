package vn.chuctu.clinic.common;

/** Giá trị gửi lên có ba trạng thái: không gửi (absent), gửi null, gửi giá trị. Thay cho undefined của JavaScript. */
public final class Opt<T> {

    private static final Opt<?> ABSENT = new Opt<>(false, null);

    private final boolean present;
    private final T value;

    private Opt(boolean present, T value) {
        this.present = present;
        this.value = value;
    }

    @SuppressWarnings("unchecked")
    public static <T> Opt<T> absent() {
        return (Opt<T>) ABSENT;
    }

    /** Có gửi; value có thể là null (gửi null hoặc chuỗi rỗng nghĩa là xoá). */
    public static <T> Opt<T> of(T value) {
        return new Opt<>(true, value);
    }

    public boolean isAbsent() {
        return !present;
    }

    public boolean isPresent() {
        return present;
    }

    /** Giá trị gửi lên, có thể null; null cũng là kết quả khi absent. */
    public T get() {
        return value;
    }
}
