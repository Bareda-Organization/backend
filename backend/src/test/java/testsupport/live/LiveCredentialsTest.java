package testsupport.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** BR-238 — 자격증명이 없을 때 기본은 건너뜀(false), {@code requireLive} 면 실패(예외)임을 고정한다. */
class LiveCredentialsTest {

    private static final Map<String, String> WITH_FALLBACK_NAMES = Map.of("NAVER_DIRECTIONS_KEY_ID", "id",
            "NAVER_DIRECTIONS_KEY", "key");

    @Test
    void 자격증명이_없으면_기본은_건너뛴다() {
        assertThat(LiveCredentials.available(Map.of(), false)).isFalse();
    }

    @Test
    void 자격증명이_없는데_requireLive_면_건너뛰지_않고_실패한다() {
        assertThatThrownBy(() -> LiveCredentials.available(Map.of(), true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("requireLive");
    }

    @Test
    void 폴백_이름의_자격증명도_인정하고_빈_값은_없는_것으로_본다() {
        assertThat(LiveCredentials.available(WITH_FALLBACK_NAMES, true)).isTrue();
        assertThat(LiveCredentials.available(Map.of("NAVER_MAPS_KEY_ID", " ", "NAVER_MAPS_KEY", "k"), false))
                .isFalse();
    }
}
