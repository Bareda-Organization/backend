package src.backend.exception.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 다른 모듈(boarding)에 내주는 미승차 케이스 요약 — {@code id}·{@code started_at}·{@code expires_at}
 * 3필드와 연락 이력 {@code contacts}(시각순, 없으면 빈 목록 — Ruling 823)다(API_SPEC §4.6·§4.7 {@code no_show_case}). 엔티티 {@code NoShowCase} 를 직접 넘기지 않는
 * 이유는 {@link src.backend.exception.command.NoShowCaseAccess} 자바독을 본다(BR-095).
 */
public record NoShowCaseView(Long caseId, OffsetDateTime startedAt, OffsetDateTime expiresAt,
        List<NoShowContactView> contacts) {
}
