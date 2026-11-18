package src.backend.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 계정 상태 보관 항목의 조회는 적중과 미적중을 각각 센다 — 질의가 나간 조회와 비운 뒤의 첫 조회가 미적중이다. */
class AccountStandingCountersTest {

    private final LostStandingSource source = mock(LostStandingSource.class);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final AccountStanding standing = new AccountStanding(source, Clock.systemUTC(), Duration.ofSeconds(5),
            registry);

    private double count(String result) {
        return registry.get("schoolbus.cache.account_standing." + result).counter().count();
    }

    @Test
    @DisplayName("첫 조회는 미적중, 같은 계정의 다음 조회는 적중, 비운 뒤 조회는 다시 미적중이다")
    void 적중과_미적중을_센다() {
        given(source.findIdsWithLostStanding(anyCollection())).willReturn(List.of());

        standing.isLost(1L);
        assertThat(count("miss")).isEqualTo(1.0);
        assertThat(count("hit")).isZero();

        standing.isLost(1L);
        assertThat(count("hit")).isEqualTo(1.0);

        standing.evict(1L);
        standing.isLost(1L);
        assertThat(count("miss")).isEqualTo(2.0);
        assertThat(count("hit")).isEqualTo(1.0);
    }
}
