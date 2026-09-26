package src.backend.global.websocket;

import java.time.OffsetDateTime;

/**
 * 공통 봉투(API_SPEC §7 "공통 봉투") — 채널 4종의 모든 방송이 이 형태로 나간다.
 * {@code application.yml} 의 {@code property-naming-strategy: SNAKE_CASE}(Ruling 104)가
 * {@code runId}→{@code run_id} · {@code occurredAt}→{@code occurred_at} 를 자동으로 만들어
 * {@code @JsonProperty} 를 붙이지 않는다.
 *
 * <p>{@code runId} 필드 타입은 {@code Long} 그대로다 — JSON 으로는
 * {@link src.backend.global.config.IdentifierJsonConfig} 가 문자열로 감싸 내보낸다(API_SPEC §1.1,
 * Ruling 332). WS 봉투도 REST 응답과 같은 전역 {@code ObjectMapper} 를 쓰므로 자동으로 적용된다.
 */
public record WebSocketEnvelope(String event, Long runId, OffsetDateTime occurredAt, Object payload) {
}
