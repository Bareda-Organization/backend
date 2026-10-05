package src.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 기준 Clock 이 DB(timestamptz)가 저장하는 마이크로초까지만 시각을 낸다 — 나노초가 남으면 응답에 실은 값과 저장 뒤 다시 읽은 값이
 * 반올림만큼 어긋난다(같은 client_key 재요청의 {@code changed_at} 이 1µs 다르던 결함, §4.6). macOS 시계는 원래 마이크로초라
 * 이 기계에서는 안 드러나므로 나노초를 가진 시계를 직접 넣어 확인한다.
 */
class ClockConfigTest {

    @Test
    @DisplayName("나노초를 가진 시계도 마이크로초 아래를 잘라 낸다 — 시간대는 그대로")
    void 서버_시각은_DB_가_저장하는_마이크로초까지만_가진다() {
        ZoneId seoul = ZoneId.of("Asia/Seoul");
        Clock nanoClock = Clock.fixed(Instant.parse("2026-10-05T03:11:29.771536789Z"), seoul);

        Clock clock = ClockConfig.toDbPrecision(nanoClock);

        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-10-05T03:11:29.771536Z"));
        assertThat(clock.getZone()).isEqualTo(seoul);
    }

    @Test
    @DisplayName("주입되는 Clock 빈이 이 마이크로초 시계다")
    void 기준_Clock_빈은_마이크로초_시계를_쓴다() {
        Clock expected = ClockConfig.toDbPrecision(Clock.system(ZoneId.of("Asia/Seoul")));

        assertThat(new ClockConfig().clock()).isEqualTo(expected);
    }
}
