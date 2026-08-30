package src.backend.routing.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Weekday;

/**
 * {@link DailyRoster} 가 중복 {@code studentId} 를 안전망으로 흡수하는지 본다
 * (Phase 6 T4 리뷰 계약 공백 · Phase 7 목표 11) — 순수 POJO 라 DB 없이 돈다.
 */
class DailyRosterTest {

    @Test
    @DisplayName("같은 studentId 가 두 번 들어가도 명단은 한 번만 싣는다")
    void collapsesDuplicateStudentId() {
        DailyRoster roster = DailyRoster.of(1L, Weekday.MON, Direction.TO_ACADEMY, List.of(10L, 10L, 20L));

        assertThat(roster.studentIds()).containsExactly(10L, 20L);
    }

    @Test
    @DisplayName("중복이 없으면 순서와 개수를 그대로 유지한다")
    void keepsOrderWhenNoDuplicates() {
        DailyRoster roster = DailyRoster.of(1L, Weekday.MON, Direction.TO_ACADEMY, List.of(30L, 10L, 20L));

        assertThat(roster.studentIds()).containsExactly(30L, 10L, 20L);
    }

    @Test
    @DisplayName("stopOverrides 는 중복 배제와 무관하게 그대로 전달된다")
    void stopOverridesUnaffectedByDedup() {
        DailyRoster roster = new DailyRoster(1L, Weekday.MON, Direction.TO_ACADEMY,
                List.of(10L, 10L), Map.of(10L, 99L));

        assertThat(roster.studentIds()).containsExactly(10L);
        assertThat(roster.stopOverrides()).containsEntry(10L, 99L);
    }
}
