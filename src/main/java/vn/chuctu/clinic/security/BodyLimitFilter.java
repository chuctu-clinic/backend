package vn.chuctu.clinic.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.chuctu.clinic.common.Errors;

/**
 * Giống express.json({ limit: '1mb' }): chỉ áp dụng cho body application/json, trước khi đọc phiên. Body quá 1 MB
 * trả đúng 500 SERVER_ERROR như bản Node (lỗi 413 của body-parser rơi vào errorHandler). Body không phải object
 * hoặc mảng, hoặc sai cú pháp, trả 400 BAD_JSON. Body rỗng đi tiếp (Node coi như {}).
 */
public class BodyLimitFilter extends OncePerRequestFilter {

    static final int LIMIT_BYTES = 1024 * 1024;

    private final JsonMapper mapper;
    private final JsonErrorWriter errors;

    public BodyLimitFilter(JsonMapper mapper, JsonErrorWriter errors) {
        this.mapper = mapper;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isJson(request)) {
            chain.doFilter(request, response);
            return;
        }
        if (request.getContentLengthLong() > LIMIT_BYTES) {
            errors.write(response, Errors.serverError());
            return;
        }
        byte[] body = request.getInputStream().readNBytes(LIMIT_BYTES + 1);
        if (body.length > LIMIT_BYTES) {
            errors.write(response, Errors.serverError());
            return;
        }
        if (!isEmpty(body) && !isObjectOrArray(body)) {
            errors.write(response, Errors.badJson());
            return;
        }
        chain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private static boolean isJson(HttpServletRequest request) {
        String type = request.getContentType();
        if (type == null) return false;
        try {
            return MediaType.parseMediaType(type).equalsTypeAndSubtype(MediaType.APPLICATION_JSON);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isEmpty(byte[] body) {
        for (byte b : body) if (b != ' ' && b != '\t' && b != '\n' && b != '\r') return false;
        return true;
    }

    /** express.json ở chế độ strict: gốc phải là object hoặc mảng; rồi parse thử để bắt lỗi cú pháp. */
    private boolean isObjectOrArray(byte[] body) {
        try {
            JsonNode node = mapper.readTree(body);
            return node.isObject() || node.isArray();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
