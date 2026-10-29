package src.backend.notification.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * JSON 의 {@code true} · {@code false} 만 boolean 으로 읽는다 — Jackson 기본은 문자열 {@code "true"} 와 숫자 {@code 1} · {@code 0} 도 변환해 주는데, 그러면 사양이
 * "boolean 이 아닌 값은 422" 라고 한 입력이 {@code 200} 으로 통과한다(API_SPEC §3.14). 다른 토큰은 형식 오류로 거절한다. 전역 설정을 바꾸지 않고 이 요청 한 곳에서만 엄격하다.
 */
public class StrictBoolean extends ValueDeserializer<Boolean> {

    @Override
    public Boolean deserialize(JsonParser parser, DeserializationContext context) {
        JsonToken token = parser.currentToken();
        if (token == JsonToken.VALUE_TRUE) {
            return Boolean.TRUE;
        }
        if (token == JsonToken.VALUE_FALSE) {
            return Boolean.FALSE;
        }
        return (Boolean) context.handleUnexpectedToken(Boolean.class, parser);
    }
}
