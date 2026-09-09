package src.backend.exception.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 미승차 연락 시도 — 한 케이스에 여러 번 발생할 수 있고, 각 시도의 결과가 대기 카운트다운 중단 판정에
 * 쓰인다(ERD §3.4 · BRD-05).
 */
@Entity
@Table(name = "no_show_contact")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NoShowContact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "no_show_case_id", nullable = false)
    private Long noShowCaseId;

    @Convert(converter = ContactAttemptType.Db.class)
    @Column(name = "attempt_type", length = 10, nullable = false)
    private ContactAttemptType attemptType;

    @Convert(converter = ContactResult.Db.class)
    @Column(name = "result", length = 10, nullable = false)
    private ContactResult result;

    @Convert(converter = NoShowDecision.Db.class)
    @Column(name = "decision", length = 10)
    private NoShowDecision decision;

    @Column(name = "attempted_at", nullable = false)
    private OffsetDateTime attemptedAt;

    @Column(name = "attempted_by", nullable = false)
    private Long attemptedBy;

    private NoShowContact(Long noShowCaseId, ContactAttemptType attemptType, ContactResult result,
            NoShowDecision decision, Long attemptedBy, OffsetDateTime attemptedAt) {
        this.noShowCaseId = noShowCaseId;
        this.attemptType = attemptType;
        this.result = result;
        this.decision = decision;
        this.attemptedBy = attemptedBy;
        this.attemptedAt = attemptedAt;
    }

    /**
     * 연락 시도 1회가 끝났을 때 결과 행을 만든다(Phase 11 목표 3, API_SPEC §4.8) — {@code decision} 은
     * 요청 본문의 선택 필드를 그대로 받아 이 행 하나에 함께 남긴다({@code no_show_contact.decision}).
     * 그 값이 {@link NoShowCase} 의 카운트다운을 실제로 멈추는지는 이 팩토리가 아니라 호출부
     * ({@code NoShowContactCommandService})가 {@link NoShowCase#resolveByAnswer}·{@link NoShowCase#decide}
     * 로 판단한다 — 이 엔티티는 시도 이력을 남기는 것만 책임진다.
     */
    public static NoShowContact forAttempt(Long noShowCaseId, ContactAttemptType attemptType,
            ContactResult result, NoShowDecision decision, Long attemptedBy, OffsetDateTime attemptedAt) {
        return new NoShowContact(noShowCaseId, attemptType, result, decision, attemptedBy, attemptedAt);
    }
}
