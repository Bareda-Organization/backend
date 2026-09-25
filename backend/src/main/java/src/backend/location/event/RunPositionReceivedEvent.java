package src.backend.location.event;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 위치 송신 1건이 저장된 뒤 발행하는 도메인 이벤트(목표 3) — 이 저장소의 다른 좌석 2개(T2 WebSocket
 * 팬아웃 · T3 근접 판정)가 그대로 구독하는 공유 계약이다.
 *
 * <p>⚠ <b>인자 순서를 바꾸지 마라.</b> {@code recordedAt}(단말이 찍은 시각)과 {@code receivedAt}
 * (서버가 받은 시각)은 <b>같은 타입({@code OffsetDateTime})이라 컴파일러가 뒤바뀜을 잡지 못한다</b> —
 * Phase 8 에서 같은 형태({@code Long} 두 개)가 뒤바뀌어 컴파일은 됐지만 수신자 조회가 0건이 된
 * 사고와 같은 함정이다. {@code RunPositionReceivedEventTest} 가 이 순서를 검사한다.
 *
 * <p>{@code currentStopName}(가장 최근 도착한 정차 항목 이름)·{@code nextEta}(그 뒤 첫 정차 항목의 계획
 * 도착 시각)는 위치를 저장한 트랜잭션이 한 번 계산해 싣는다(BR-100) — 커밋 뒤 두 리스너(Redis 최신 좌표 ·
 * WebSocket 방송)가 같은 조회를 각자 반복하지 않게 한다. 둘 다 없으면 {@code null}.
 */
public record RunPositionReceivedEvent(
        Long runId,
        BigDecimal lat,
        BigDecimal lng,
        OffsetDateTime recordedAt,
        OffsetDateTime receivedAt,
        Long academyId,
        String currentStopName,
        OffsetDateTime nextEta) {
}
