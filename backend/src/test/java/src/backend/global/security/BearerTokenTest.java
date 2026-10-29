package src.backend.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** HTTP 필터와 STOMP CONNECT 가 같은 규칙으로 {@code Authorization} 헤더에서 토큰을 꺼낸다. */
class BearerTokenTest {

    @Test
    @DisplayName("Bearer 접두사 뒤의 값을 돌려준다")
    void 접두사_뒤의_값을_돌려준다() {
        assertThat(BearerToken.from("Bearer abc.def.ghi")).isEqualTo("abc.def.ghi");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "Bearer", "Bearer ", "Basic abc", "bearer abc"})
    @DisplayName("헤더가 없거나 Bearer 형식이 아니거나 토큰이 비면 null")
    void 형식이_아니면_null(String header) {
        assertThat(BearerToken.from(header)).isNull();
    }
}
