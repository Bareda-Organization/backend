package src.backend.location.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Redis 최신 좌표 값(키 {@code run:{runId}:position}, 목표 3) — 이 저장소의 다른 좌석 3개(T2·T3·T4)가
 * 소비하는 공유 계약이다. 필드 4종(lat·lng·recordedAt·receivedAt)과 이름은 임의로 바꾸지 않는다.
 *
 * @param currentStopName 가장 최근 도착 처리된 정차 항목의 이름 — 아직 아무 곳에도 도착하지 않았으면
 *                         {@code null}
 */
public record RunPositionRedisValue(
        BigDecimal lat,
        BigDecimal lng,
        OffsetDateTime recordedAt,
        OffsetDateTime receivedAt,
        String currentStopName) {
}
