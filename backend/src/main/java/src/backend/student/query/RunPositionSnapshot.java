package src.backend.student.query;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * T1 이 {@code run:{runId}:position} 키에 쓰는 위치 캐시 값을 그대로 옮긴 읽기 전용 사본이다.
 *
 * <p>필드 이름은 T1 의 계약 문서에 적힌 그대로다(평문 camelCase — {@code lat}·{@code lng}·
 * {@code recordedAt}·{@code receivedAt}·{@code currentStopName}). {@code recordedAt} 은 위치 발신
 * 장비가 찍은 시각, {@code receivedAt} 은 서버 수신 시각이다 — Ruling 208 의 2분 유실 판정은
 * {@code receivedAt} 기준이라 이 태스크는 그 필드만 쓰고 {@code recordedAt} 은 그대로 지나친다
 * (계약을 소비만 하고 고치지 않는다는 T1 경계 — 계약이 실제와 다르면 침묵 수정 대신 보고한다).
 */
public record RunPositionSnapshot(BigDecimal lat, BigDecimal lng, OffsetDateTime recordedAt,
        OffsetDateTime receivedAt, String currentStopName) {
}
