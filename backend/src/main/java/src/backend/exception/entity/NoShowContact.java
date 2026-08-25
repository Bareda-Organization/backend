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
            Long attemptedBy, OffsetDateTime attemptedAt) {
        this.noShowCaseId = noShowCaseId;
        this.attemptType = attemptType;
        this.result = result;
        this.attemptedBy = attemptedBy;
        this.attemptedAt = attemptedAt;
    }

    /** 연락 시도 1회가 끝났을 때 결과 행을 만든다 — 카운트다운 중단 판정({@code decision})은 도메인 Phase 담당. */
    public static NoShowContact forAttempt(Long noShowCaseId, ContactAttemptType attemptType,
            ContactResult result, Long attemptedBy, OffsetDateTime attemptedAt) {
        return new NoShowContact(noShowCaseId, attemptType, result, attemptedBy, attemptedAt);
    }
}
