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
}
