package src.backend.monitoring.query;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Admin 접두 임시본 — {@code run:{runId}:position} Redis 키를 읽는 §6.8 전용 사본이다
 * ({@code student.query.RunPositionSnapshot} 과 같은 계약을 그대로 옮긴다).
 *
 * <p>T1 이 {@code monitoring/query/} 에 공용 판정 클래스를 만들어 조율자가 이름을 전파하면 이
 * 임시본은 지운다(p13-task-t2.md §3). 필드 이름은 {@code RunPositionRedisValue} 계약과 같은
 * 평문 camelCase 다 — {@code recordedAt} 은 발신 시각, {@code receivedAt} 은 서버 수신 시각.
 */
public record AdminRunPositionSnapshot(BigDecimal lat, BigDecimal lng, OffsetDateTime recordedAt,
        OffsetDateTime receivedAt, String currentStopName) {
}
