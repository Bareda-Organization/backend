package testsupport.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * 송신 템플릿 목이 받은 봉투를 선 위의 JSON 으로 읽는 도우미. Redis 중계를 거친 봉투의 payload 는 이미 직렬화된 JSON 트리라, 시험용 매퍼로 봉투를 바로 트리로 바꾸면 그 트리가
 * 통째로 한 번 더 빈으로 직렬화된다 — 앱이 쓰는 키 이름 규칙(SNAKE_CASE)으로 글자로 만든 뒤 읽는다.
 */
public final class WireJson {

    private static final tools.jackson.databind.ObjectMapper WRITER = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();

    private static final ObjectMapper READER = new ObjectMapper();

    private WireJson() {
    }

    /** 봉투(또는 어떤 방송 객체든)를 선 위의 JSON 트리로 읽는다. */
    public static JsonNode of(Object envelope) throws Exception {
        return READER.readTree(WRITER.writeValueAsString(envelope));
    }
}
