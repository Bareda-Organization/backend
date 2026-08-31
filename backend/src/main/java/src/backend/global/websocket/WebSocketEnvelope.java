package src.backend.global.websocket;

import java.time.OffsetDateTime;

/**
 * 공통 봉투(API_SPEC §7 "공통 봉투") — 채널 4종의 모든 방송이 이 형태로 나간다.
 * {@code application.yml} 의 {@code property-naming-strategy: SNAKE_CASE}(Ruling 104)가
 * {@code runId}→{@code run_id} · {@code occurredAt}→{@code occurred_at} 를 자동으로 만들어
 * {@code @JsonProperty} 를 붙이지 않는다.
 *
 * <p>{@code runId} 는 spec 표기가 {@code string} 이지만, 이 저장소는 REST 응답 어디서도 ID 를
 * 문자열로 감싸지 않는다({@code Long} 그대로 JSON 숫자로 직렬화) — 이 관례를 따른다(판단 근거로
 * 보고에 남긴다).
 */
public record WebSocketEnvelope(String event, Long runId, OffsetDateTime occurredAt, Object payload) {
}
