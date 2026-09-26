package src.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import src.backend.academy.dto.AcademySummaryResponse;

/**
 * 응답 식별자 문자열 전환 규약 시험(API_SPEC §1.1, Ruling 332) — 실제 직렬화로 검사한다. 목록에
 * 없는 새 응답 레코드라도 {@code id} 또는 {@code *Id} 이름 규약을 따르는 {@code Long} 필드면
 * {@link IdentifierJsonConfig} 가 자동으로 잡으므로, 레코드마다 시험을 추가할 필요가 없다.
 */
class IdentifierJsonConfigTest {

    private final JsonMapper mapper = JsonMapper.builder()
            .addModule(new IdentifierJsonConfig().identifierStringModule())
            .build();

    /** 이 시험만을 위한 새 레코드 — 목록에 없어도 이름 규약만으로 잡히는지 증명한다. */
    private record NewRecordResponse(Long id, Long ownerId, int capacity) {
    }

    @Test
    void 새_레코드의_식별자_필드도_문자열로_직렬화된다() {
        String json = mapper.writeValueAsString(new NewRecordResponse(1L, 2L, 40));

        assertThat(json).contains("\"id\":\"1\"").contains("\"ownerId\":\"2\"");
    }

    @Test
    void 식별자가_아닌_숫자는_그대로_숫자로_남는다() {
        String json = mapper.writeValueAsString(new NewRecordResponse(1L, 2L, 40));

        assertThat(json).contains("\"capacity\":40");
    }

    @Test
    void 기존_응답_레코드의_id도_문자열이고_인원수는_숫자로_남는다() {
        AcademySummaryResponse response = new AcademySummaryResponse(7L, "AC01", "학원", "서울", 3L, 40L, "active");

        String json = mapper.writeValueAsString(response);

        assertThat(json).contains("\"id\":\"7\"").contains("\"staffCount\":3").contains("\"userCount\":40");
    }
}
