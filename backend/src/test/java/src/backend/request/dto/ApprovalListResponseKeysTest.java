package src.backend.request.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 승인 대기 목록 응답(API_SPEC §5.5)의 JSON 키 — 페이징 봉투의 다섯 키에 {@code pending_count} 가 더해진 여섯 개 그대로여야 한다.
 */
@SpringBootTest
class ApprovalListResponseKeysTest {

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    @DisplayName("승인 목록 응답의 키는 items · pending_count · page · size · total_count · has_next 여섯 개다")
    void 승인_목록_응답의_키는_그대로다() {
        ApprovalListResponse response = ApprovalListResponse.of(new PageImpl<>(List.of("x"), PageRequest.of(0, 1), 5),
                List.of(), 7);

        JsonNode json = jsonMapper.valueToTree(response);

        assertThat(json.propertyNames()).containsExactlyInAnyOrder("items", "pending_count", "page", "size",
                "total_count", "has_next");
        assertThat(json.get("pending_count").asLong()).isEqualTo(7);
        assertThat(json.get("page").asInt()).isZero();
        assertThat(json.get("total_count").asLong()).isEqualTo(5);
        assertThat(json.get("has_next").asBoolean()).isTrue();
    }
}
