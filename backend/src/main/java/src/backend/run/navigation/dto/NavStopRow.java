package src.backend.run.navigation.dto;

import java.math.BigDecimal;

import src.backend.global.common.enums.ChangeType;

/**
 * {@code run_stop} 1행을 정차지·경유 지점(둘 중 실제로 채워진 쪽) 이름·좌표와 함께 읽은 조회
 * 전용 투영(API_SPEC §4.16) — {@code stop_id}·{@code waypoint_id} 는 배타적이라(ERD
 * {@code ck_run_stop_target_exclusive}) 어느 쪽이 채워졌든 이름·좌표 한 쌍만 나온다.
 */
public record NavStopRow(Long id, int seq, ChangeType change, boolean arrived, Long stopId, String name,
        BigDecimal lat, BigDecimal lng) {
}
