package src.backend.boarding.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

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
 * 승하차 상태 변경 이력 — 되돌리기가 이력 보존을 전제하며, 오프라인 큐 멱등키({@code client_key})의 보관처이기도 하다
 * (ERD §3.4 · BRD-05·06 · API_SPEC §1.7).
 *
 * <p>{@code run_rider}·{@code account} 는 논리적 부모이나 DB FK 가 미설정이다(ERD §4.2 — 대량 적재 +
 * 되돌리기 감사용이라 원본 정리 후에도 존치). 그래서 {@code runRiderId}·{@code changedBy} 는 예외 없이 {@code Long} 이다.
 */
@Entity
@Table(name = "rider_status_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RiderStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "run_rider_id", nullable = false)
    private Long runRiderId;

    /** 이 컬럼과 {@link #toStatus} 는 CHECK 가 부재해 스키마가 값을 보장하지 않는다(조율자 Ruling 55) —
     * 잘못된 값은 쓸 때가 아니라 이 행을 다시 읽어 {@link RiderStatus.Db#convertToEntityAttribute} 를
     * 타는 순간 {@link Enum#valueOf} 에서 터진다. */
    @Convert(converter = RiderStatus.Db.class)
    @Column(name = "from_status", length = 10)
    private RiderStatus fromStatus;

    @Convert(converter = RiderStatus.Db.class)
    @Column(name = "to_status", length = 10, nullable = false)
    private RiderStatus toStatus;

    @Column(name = "is_revert", nullable = false)
    private boolean isRevert;

    @Column(name = "reason", length = 200)
    private String reason;

    @Convert(converter = VerifyMethod.Db.class)
    @Column(name = "verify_method", length = 10)
    private VerifyMethod verifyMethod;

    @Column(name = "client_key")
    private UUID clientKey;

    @Column(name = "occurred_at")
    private OffsetDateTime occurredAt;

    @Column(name = "changed_at", nullable = false)
    private OffsetDateTime changedAt;

    @Convert(converter = ActorType.Db.class)
    @Column(name = "actor_type", length = 10, nullable = false)
    private ActorType actorType;

    @Column(name = "changed_by")
    private Long changedBy;

    private RiderStatusHistory(Long runRiderId, RiderStatus fromStatus, RiderStatus toStatus,
            ActorType actorType, OffsetDateTime changedAt) {
        this.runRiderId = runRiderId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actorType = actorType;
        this.changedAt = changedAt;
        this.isRevert = false;
    }

    /**
     * 탑승자 상태가 전이될 때 이력 행을 만든다 — 되돌리기 여부·검증 수단·멱등키 등은 이 팩토리가 다루지 않는다
     * (Phase 1 은 필드 매핑까지, 상태 전이 정책은 도메인 Phase 담당). 파라미터 5개는 §20.2 기준(4개)을
     * 넘지만 NN 필드 전부가 생성 시점에 필요해 record 파라미터 객체 도입 대신 규약 §3(팩토리 분화 금지)을 우선했다.
     */
    public static RiderStatusHistory forTransition(Long runRiderId, RiderStatus fromStatus,
            RiderStatus toStatus, ActorType actorType, OffsetDateTime changedAt) {
        return new RiderStatusHistory(runRiderId, fromStatus, toStatus, actorType, changedAt);
    }
}
