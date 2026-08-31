package src.backend.boarding.event;

import java.time.OffsetDateTime;

/**
 * 회차가 방금 {@code finished} 로 자동 전이됐음을 알리는 도메인 이벤트(C-15, API_SPEC §4.10 {@code
 * run_ended}) — "관계자 종료 통지"(USER_FLOWS UF-D-04)의 재료다.
 *
 * <p>{@link src.backend.run.command.RunCompletionService#completeIfAllAlighted} 가 {@code true} 를
 * 반환한 마지막 하차 처리 호출자만 이 이벤트를 발행한다 — {@code false}(아직 잔류자가 있거나 하원·
 * 종료 대상이 아님)일 때는 발행하지 않는다.
 *
 * <p>{@code autoAlightedCount} 는 §7.1 {@code run_ended} payload 의 필수 필드다 — T3 는 이 값을
 * payload 에 실을 리스너를 만들지 않고(목표 4·7·11·12·13·14 어디에도 검사 대상으로 없었다) 필드
 * 자체도 비워 뒀다. T2 가 소유한 목표 4·10 의 WebSocket 방송 시점에 필드와 리스너를 함께 채운다
 * ({@link src.backend.boarding.repository.RunRiderRepository#countByRunIdAndStatus} 재사용, 새
 * 쿼리 없음).
 */
public record RunEndedEvent(Long runId, Long academyId, OffsetDateTime finishedAt, long autoAlightedCount) {
}
