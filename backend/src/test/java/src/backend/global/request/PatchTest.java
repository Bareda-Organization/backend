package src.backend.global.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/** {@code PATCH} 본문의 키 없음 · 명시적 {@code null} · 값을 가르고(Ruling 390), 필수(●)·선택(○) 항목의 해석을 고정한다. */
class PatchTest {

    private record Body(Patch<String> name, Patch<Integer> age) {
    }

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    @DisplayName("키가 없으면 null, 명시적 null 이면 값이 null 인 Patch, 값이 있으면 그 값의 Patch")
    void 키_없음과_null_과_값을_가른다() {
        Body absent = mapper.readValue("{}", Body.class);
        Body explicitNull = mapper.readValue("{\"name\":null,\"age\":null}", Body.class);
        Body valued = mapper.readValue("{\"name\":\"가\",\"age\":7}", Body.class);

        assertThat(absent.name()).as("키 없음").isNull();
        assertThat(absent.age()).isNull();
        assertThat(explicitNull.name()).as("명시적 null").isNotNull();
        assertThat(explicitNull.name().value()).isNull();
        assertThat(explicitNull.age()).isNotNull();
        assertThat(explicitNull.age().value()).isNull();
        assertThat(valued.name().value()).isEqualTo("가");
        assertThat(valued.age().value()).isEqualTo(7);
    }

    @Test
    @DisplayName("필수 항목 — 키 없음은 현재 값을 유지하고 null·빈 문자열은 422 이며 값은 그 값이다")
    void 필수_항목의_해석() {
        assertThat(Patch.required(null, "현재")).isEqualTo("현재");
        assertThat(Patch.required(Patch.of("새 값"), "현재")).isEqualTo("새 값");
        assertThatThrownBy(() -> Patch.required(Patch.of(null), "현재")).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> Patch.required(Patch.of(""), "현재")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> Patch.required(Patch.of("  "), "현재")).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("선택 항목 — 키 없음은 현재 값을 유지하고 null·빈 문자열은 지우며 값은 그 값이다")
    void 선택_항목의_해석() {
        assertThat(Patch.optional(null, "현재")).isEqualTo("현재");
        assertThat(Patch.optional(Patch.of(null), "현재")).isNull();
        assertThat(Patch.optional(Patch.of(""), "현재")).isNull();
        assertThat(Patch.optional(Patch.of("  "), "현재")).isNull();
        assertThat(Patch.optional(Patch.of("새 값"), "현재")).isEqualTo("새 값");
        assertThat(Patch.optional(Patch.of(0), 5)).as("숫자 0 은 지우는 값이 아니다").isZero();
    }

    @Test
    @DisplayName("clears — 키가 있고 값이 null 이거나 빈 문자열일 때만 참")
    void clears_판정() {
        assertThat(Patch.clears(null)).as("키 없음").isFalse();
        assertThat(Patch.clears(Patch.of(null))).isTrue();
        assertThat(Patch.clears(Patch.of(""))).isTrue();
        assertThat(Patch.clears(Patch.of("x"))).isFalse();
        assertThat(Patch.clears(Patch.of(0))).isFalse();
    }
}
