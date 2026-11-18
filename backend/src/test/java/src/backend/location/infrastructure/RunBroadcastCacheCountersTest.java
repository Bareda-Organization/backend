package src.backend.location.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.List;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 회차 방송 보관 항목의 조회는 적중과 미적중을 각각 센다 — 읽어 들인 조회와 비운 뒤의 첫 조회가 미적중이다. */
class RunBroadcastCacheCountersTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final RunBroadcastCache cache = new RunBroadcastCache(Clock.systemUTC(), registry);

    private double count(String result) {
        return registry.get("schoolbus.cache.run_broadcast." + result).counter().count();
    }

    @Test
    @DisplayName("첫 조회는 미적중, 같은 회차의 다음 조회는 적중, 비운 뒤 조회는 다시 미적중이다")
    void 적중과_미적중을_센다() {
        cache.students(1L, () -> List.of(1L));
        assertThat(count("miss")).isEqualTo(1.0);
        assertThat(count("hit")).isZero();

        cache.students(1L, () -> List.of(1L));
        assertThat(count("hit")).isEqualTo(1.0);

        cache.evictStudents(1L);
        cache.students(1L, () -> List.of(1L));
        assertThat(count("miss")).isEqualTo(2.0);
        assertThat(count("hit")).isEqualTo(1.0);
    }
}
