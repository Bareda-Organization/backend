package src.backend.location.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Redis 최신 좌표 값(키 {@code run:{runId}:position}, 목표 3) — 이 저장소의 다른 좌석 3개(T2·T3·T4)가
 * 소비하는 공유 계약이다. 필드 5종(lat·lng·recordedAt·receivedAt·currentStopName)과 이름은 임의로
 * 바꾸지 않는다.
 *
 * <p>⚠ <b>와이어 포맷 — 다형 타입 태그 없는 평문 camelCase JSON.</b>({@code {"lat":37.5,...}}, Phase 10
 * 게이트 리뷰 R1 Critical 정정). {@link src.backend.location.command.RunPositionRedisListener} 가
 * 다형 타입 태그를 심는 {@code RedisTemplate<String,Object>}({@code GenericJacksonJsonRedisSerializer})
 * 로 쓰던 시절에는 {@code @class} 태그가 붙고 {@code BigDecimal} 필드가
 * {@code ["java.math.BigDecimal", 37.5]} 배열로 나갔다 — T3({@code RunPositionReader})·
 * T4({@code RunPositionCache}) 는 둘 다 평문을 기대해 그 값을 파싱하지 못했다(T3 는 조용히
 * {@code Optional.empty()}, T4 는 {@code 500}). <b>이 키에 값을 쓸 때 다시 다형 직렬화기를 쓰지
 * 마라</b> — 같은 사고가 재발한다.
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
