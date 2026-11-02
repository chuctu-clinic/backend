package vn.chuctu.clinic.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import vn.chuctu.clinic.common.ApiException;

/** Ghi lỗi JSON từ các lớp lọc, nơi GlobalExceptionHandler không với tới. */
@Component
public class JsonErrorWriter {

    private final JsonMapper mapper;

    public JsonErrorWriter(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public void write(HttpServletResponse response, ApiException e) throws IOException {
        response.setStatus(e.status());
        response.setContentType("application/json;charset=UTF-8");
        response.getOutputStream().write(mapper.writeValueAsBytes(e.toBody()));
    }
}
