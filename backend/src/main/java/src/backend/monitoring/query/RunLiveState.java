package src.backend.monitoring.query;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * {@link RunLiveStateResolver} 의 판정 결과 — §5.18(관계자, T1)·§6.8(관리자, T2)이 공유하는 중간
 * 산출물이며 <b>응답 DTO 가 아니다</b>. 두 절의 필드 이름이 다르다(§5.18 {@code recorded_at} ·
 * §6.8 {@code received_at}, 같은 Redis 값의 서로 다른 시각 필드를 각자 문서화된 이름으로 노출하는
 * 것뿐이지 정본 불일치가 아니다) — 그래서 이 레코드는 중립적인 필드 이름만 갖고, 절 이름을 붙인
 * 응답 형태로의 매핑은 각 좌석의 소비 코드가 한다.
 *
 * @param lat 위도 — 유실 상태({@link #stale} true)여도 마지막으로 읽은 값을 그대로 담는다. null 로
 *            지울지는 소비 측 응답 규칙이 정한다
 * @param lng 경도 — {@link #lat} 과 같은 원칙
 * @param recordedAt 위치 발신 장비가 찍은 시각
 * @param receivedAt 서버가 그 값을 수신한 시각 — 유실 판정의 기준(Ruling 208)
 * @param stale {@code receivedAt} 이 없거나 {@code StudentBusPositionQueryService.STALE_THRESHOLD}
 *              이상 지났으면 참
 * @param currentStopId 도착 처리된 정차 중 {@code seq} 최댓값 — 아직 아무 곳에도 도착하지 않았으면 null
 * @param nextStopId 미도착이고 건너뛰지 않은 정차 중 {@code seq} 최솟값 — 전 구간 도착 완료면 null
 */
public record RunLiveState(
        BigDecimal lat,
        BigDecimal lng,
        OffsetDateTime recordedAt,
        OffsetDateTime receivedAt,
        boolean stale,
        Long currentStopId,
        Long nextStopId) {
}
