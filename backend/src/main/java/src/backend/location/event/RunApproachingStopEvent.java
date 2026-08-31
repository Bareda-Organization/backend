package src.backend.location.event;

import java.time.OffsetDateTime;

/**
 * 다음 미도착 승하차지 300m 이내 최초 진입을 알리는 도메인 이벤트(NTF-04, API_SPEC §4.12
 * Ruling 207) — 그 정차지에 배정된 학생 1명당 1건 발행한다({@code RunAutoAlightedEvent} 와 같은
 * 근거: 알림도 학생당 1행이라, 정차지 단위로 묶어 하나만 발행하면 그 이벤트를 처리하는 쪽이 다시
 * 명단을 순회해야 하고 그 순회에서 한 명이 빠져도 발행 건수만으로는 드러나지 않는다).
 *
 * <p>{@code stopId} 는 알림 문구 조립에 쓰지 않는다({@code arrive} 알림 본문에 정차지 이름을 싣지
 * 않는 minimalism — {@code RunAutoAlightedComposer} 와 같은 판단) — 조건부 UPDATE 선점이 이미
 * 정차지 단위였음을 리스너 쪽에서도 추적할 수 있도록 이벤트에는 남겨 둔다(dedup_key 구성 요소).
 */
public record RunApproachingStopEvent(Long runId, Long academyId, Long studentId, Long stopId,
        OffsetDateTime judgedAt) {
}
