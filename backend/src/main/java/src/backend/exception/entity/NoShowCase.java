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
 * 미승차 에스컬레이션 케이스 — 대기 만료 시각을 컬럼으로 고정해 학원 설정(대기 한도)이 나중에 바뀌어도
 * 발생 당시 판정 기준을 재현한다(ERD §3.4 · BRD-05).
 *
 * <p>{@code run_rider_id} 는 UNIQUE 라 탑승자당 케이스가 최대 1개다. 시각 컬럼은 전부 도메인 값이라
 * (대기 시작·만료·해소·에스컬레이션·생성) auditing 대상이 아니며(엔티티 작성 규약 §4.4, Ruling 62),
 * 전부 평범한 필드로 두고 팩토리 파라미터로 받는다 — 호출부가 {@code Clock} 에서 얻어 넘긴다.
 */
@Entity
@Table(name = "no_show_case")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NoShowCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "run_rider_id", nullable = false)
    private Long runRiderId;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Convert(converter = NoShowDecision.Db.class)
    @Column(name = "decision", length = 10)
    private NoShowDecision decision;

    @Column(name = "escalated_at")
    private OffsetDateTime escalatedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    private NoShowCase(Long runRiderId, OffsetDateTime startedAt, OffsetDateTime expiresAt,
            OffsetDateTime createdAt) {
        this.runRiderId = runRiderId;
        this.startedAt = startedAt;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    /** 대기 카운트다운이 시작될 때(미승차 감지 시점) 생성한다 — 판단·연락·에스컬레이션은 도메인 Phase 담당. */
    public static NoShowCase forRunRider(Long runRiderId, OffsetDateTime startedAt, OffsetDateTime expiresAt,
            OffsetDateTime createdAt) {
        return new NoShowCase(runRiderId, startedAt, expiresAt, createdAt);
    }

    /**
     * 연락이 응답(answered)으로 끝났을 때 케이스를 종결한다(목표 3, API_SPEC §4.8
     * "result=answered 면 카운트다운 중단"). {@code decision} 값과 무관하게 항상 종결한다 — 응답을
     * 받았다는 사실 자체가 대기 이유를 없앤다.
     */
    public void resolveByAnswer(OffsetDateTime resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    /**
     * 최종 판단을 기록한다(목표 3, API_SPEC §4.8 {@code decision} "3분 경과 후 최종 판단").
     *
     * <p>{@link NoShowDecision#DEPART} 만 케이스를 종결한다(대기 없이 출발이 곧 최종 처리다).
     * {@link NoShowDecision#RETRY} 는 종결하지 않는다 — 이미 대기 시간이 지난 뒤에만 제출되는
     * 값이라({@code expires_at} 은 그대로 과거) 종결하지 않으면 다음 스케줄러 틱이 즉시
     * 에스컬레이션 대상으로 다시 집는다. 연락은 계속 시도하되 관계자에게도 알리는 것이 RETRY 의
     * 실제 의도와 맞는다는 판단 — 확신 없는 지점으로 보고에 남긴다.
     */
    public void decide(NoShowDecision decision, OffsetDateTime decidedAt) {
        this.decision = decision;
        if (decision == NoShowDecision.DEPART) {
            this.resolvedAt = decidedAt;
        }
    }

    /**
     * 대기 만료 + 무응답을 관계자에게 보고했음을 기록한다(목표 1).
     *
     * <p>스케줄러가 실제로 행을 갱신하는 경로는 이 메서드가 아니라
     * {@code NoShowCaseRepository#escalateIfDue}(조건부 UPDATE, {@code escalated_at IS NULL AND
     * resolved_at IS NULL AND expires_at <= :now})다 — 동시성 하에서 두 스케줄러 틱이 같은 케이스를
     * 중복 집는 것을 막는 재확인이 그 WHERE 절 자체이기 때문이다({@code ChangeRequestAutoRejectionPersistence}
     * 와 같은 전례: {@code ChangeRequest.autoReject()} 도 엔티티 메서드지만 실제 쓰기 경로는 raw UPDATE 다).
     * 이 메서드는 그 자리를 대체하지 않는다 — 도메인 불변조건을 단위 시험으로 검증하기 위한 자리다.
     */
    public void escalate(OffsetDateTime escalatedAt) {
        this.escalatedAt = escalatedAt;
    }
}
