package vn.chuctu.clinic.auth;

/** Gửi một thư gồm bản văn bản và bản HTML (multipart/alternative). Bản thật dùng SMTP; kiểm thử thay bằng FakeMail nên không gửi gì ra ngoài. */
public interface MailSender {

    /** Ném RuntimeException nếu gửi không được. {@code from} có thể có dạng "Tên <địa chỉ>". */
    void send(String to, String from, String subject, String text, String html);
}
