package vn.chuctu.clinic.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Chuyển mọi lỗi thành {"error":{...}} như errorHandler trong middleware/error.js. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final AppProperties config;

    public GlobalExceptionHandler(AppProperties config) {
        this.config = config;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handle(Exception e) {
        ApiException api = translate(e);
        return ResponseEntity.status(api.status()).contentType(MediaType.APPLICATION_JSON).body(api.toBody());
    }

    private ApiException translate(Exception e) {
        if (e instanceof ApiException api) return api;
        if (e instanceof HttpMessageNotReadableException) return Errors.badJson();
        // Mã trong URL sai kiểu (CastError của Mongoose)
        if (e instanceof MethodArgumentTypeMismatchException) return Errors.notFound();
        // Đường dẫn lạ, hoặc sai phương thức: Express không có route nên cũng 404
        if (e instanceof NoResourceFoundException || e instanceof NoHandlerFoundException
                || e instanceof HttpRequestMethodNotSupportedException) {
            return Errors.notFound("Không tìm thấy đường dẫn.");
        }
        // Mã 11000 của MongoDB
        if (e instanceof DuplicateKeyException || e instanceof DataIntegrityViolationException) {
            return Errors.conflict("Dữ liệu đã tồn tại.", "DUPLICATE");
        }
        if (!config.isTest) log.error("Lỗi không lường trước", e);
        return Errors.serverError();
    }
}
