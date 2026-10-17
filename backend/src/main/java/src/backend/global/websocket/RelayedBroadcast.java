package src.backend.global.websocket;

import java.time.OffsetDateTime;
import java.util.List;

/** Redis 중계를 거쳐 모든 인스턴스가 받는 이벤트 1건 — 받은 쪽은 이 중 자기 구독자가 있는 목적지에만 보낸다. */
public record RelayedBroadcast(String event, Long runId, OffsetDateTime occurredAt, List<BroadcastDelivery> deliveries) {
}
