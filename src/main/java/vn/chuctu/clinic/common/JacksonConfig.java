package vn.chuctu.clinic.common;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

/** Thời điểm trong JSON luôn là ISO UTC đúng 3 chữ số mili giây (như Date.toJSON()), kể cả khi tròn giây. */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

    private static final DateTimeFormatter MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    @Bean
    SimpleModule instantMillisModule() {
        SimpleModule module = new SimpleModule("instantMillis");
        module.addSerializer(Instant.class, new ValueSerializer<Instant>() {
            @Override
            public void serialize(Instant value, JsonGenerator gen, SerializationContext ctxt) {
                gen.writeString(MILLIS.format(value));
            }
        });
        return module;
    }
}
