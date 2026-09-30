package src.backend.global.config;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.BeanPropertyWriter;
import tools.jackson.databind.ser.ValueSerializerModifier;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * 응답 본문의 식별자(id·*Id)를 JSON 문자열로 낸다(API_SPEC §1.1, Ruling 332 — Ruling 275 미결 해소).
 *
 * <p>레코드마다 필드 타입을 {@code String} 으로 바꾸지 않는다 — 자바 코드는 그대로 {@code Long} 을
 * 쓰고, 이름 규약({@code id} 또는 {@code ...Id})과 타입만으로 판별하는 전역 직렬화 모듈 하나가
 * 문자열로 감싼다. 그래서 새 응답 레코드에 식별자 필드가 늘어도 이 클래스를 고칠 필요가 없고
 * {@code IdentifierJsonConfigTest} 가 자동으로 잡는다. 정원·인원·분처럼 {@code Id} 로 끝나지 않는
 * 숫자는 이 모듈이 건드리지 않아 그대로 JSON 숫자로 남는다. 요청(역직렬화) 쪽은 건드리지 않으므로
 * 문자열·숫자 요청 본문을 계속 둘 다 받는다({@code Ruling 332}).
 */
@Configuration
public class IdentifierJsonConfig {

    private static final Pattern ID_PROPERTY_NAME = Pattern.compile("^(id|.*Id|.*By)$");

    /** Spring Boot 가 {@code JacksonModule} 빈을 전역 {@code ObjectMapper} 에 자동 등록한다. */
    @Bean
    public JacksonModule identifierStringModule() {
        SimpleModule module = new SimpleModule("IdentifierString");
        module.setSerializerModifier(new ValueSerializerModifier() {
            @Override
            public List<BeanPropertyWriter> changeProperties(SerializationConfig config,
                    BeanDescription.Supplier beanDesc, List<BeanPropertyWriter> beanProperties) {
                for (BeanPropertyWriter writer : beanProperties) {
                    if (isLongIdentifierField(writer)) {
                        writer.assignSerializer(ToStringSerializer.instance);
                    }
                }
                return beanProperties;
            }
        });
        return module;
    }

    private static boolean isLongIdentifierField(BeanPropertyWriter writer) {
        JavaType type = writer.getType();
        boolean isLongType = type.hasRawClass(Long.class) || type.hasRawClass(long.class);
        return isLongType && ID_PROPERTY_NAME.matcher(writer.getMember().getName()).matches();
    }
}
