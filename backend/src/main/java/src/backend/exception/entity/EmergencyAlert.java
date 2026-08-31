package src.backend.exception.entity;

import java.math.BigDecimal;
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

import src.backend.global.common.enums.ManagerRole;

/**
 * 비상 알림 — 위치·탑승자 수·발신 시각을 스냅샷으로 고정해 원본(run·account·academy)이 사라져도
 * 신고 당시 상황을 그대로 재현한다(ERD §3.5 · BRD-07).
 *
 * <p>{@code academy_id}·{@code run_id}·{@code raised_by} 는 논리적 부모이나 DB FK 가 미설정이다
 * (ERD §4.2 — 스냅샷 성격상 원본 정리와 보존 주기가 다르다). {@code raised_by_role} 은 CHECK 가 부재해
 * 스키마가 값을 보장하지 않는다(Ruling 55) — 잘못된 값은 이 행을 다시 읽는 순간
 * {@link ManagerRole.Db#convertToEntityAttribute} 의 {@link Enum#valueOf} 에서 터진다.
 */
@Entity
@Table(name = "emergency_alert")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmergencyAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "academy_id", nullable = false)
    private Long academyId;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "bus_no", length = 20, nullable = false)
    private String busNo;

    @Column(name = "raised_by", nullable = false)
    private Long raisedBy;

    @Convert(converter = ManagerRole.Db.class)
    @Column(name = "raised_by_role", length = 20, nullable = false)
    private ManagerRole raisedByRole;

    @Convert(converter = EmergencyType.Db.class)
    @Column(name = "type", length = 20, nullable = false)
    private EmergencyType type;

    @Column(name = "memo", columnDefinition = "text")
    private String memo;

    @Column(name = "lat", precision = 9, scale = 6)
    private BigDecimal lat;

    @Column(name = "lng", precision = 9, scale = 6)
    private BigDecimal lng;

    @Column(name = "rider_count", nullable = false)
    private Integer riderCount;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "client_key", nullable = false)
    private UUID clientKey;

    @Column(name = "acked_by")
    private Long ackedBy;

    @Column(name = "acked_at")
    private OffsetDateTime ackedAt;

    @Column(name = "canceled_at")
    private OffsetDateTime canceledAt;

    private EmergencyAlert(Long academyId, Long runId, String busNo, Long raisedBy,
            ManagerRole raisedByRole, EmergencyType type, Integer riderCount,
            OffsetDateTime occurredAt, OffsetDateTime receivedAt, UUID clientKey) {
        this.academyId = academyId;
        this.runId = runId;
        this.busNo = busNo;
        this.raisedBy = raisedBy;
        this.raisedByRole = raisedByRole;
        this.type = type;
        this.riderCount = riderCount;
        this.occurredAt = occurredAt;
        this.receivedAt = receivedAt;
        this.clientKey = clientKey;
    }

    /**
     * 기사·동승자가 비상 버튼을 눌러 신고가 서버에 도달했을 때 생성한다 — 위치·메모는 선택값이라 팩토리에서
     * 다루지 않는다. 파라미터 10개는 §20.2 기준(4개)을 크게 넘지만, NN 필드 전부가 신고 접수 시점에
     * 필요하고 스냅샷 성격상 나중에 채울 수 없어 규약 §3(팩토리 분화 금지)을 우선했다.
     */
    public static EmergencyAlert onRaise(Long academyId, Long runId, String busNo, Long raisedBy,
            ManagerRole raisedByRole, EmergencyType type, Integer riderCount,
            OffsetDateTime occurredAt, OffsetDateTime receivedAt, UUID clientKey) {
        return new EmergencyAlert(academyId, runId, busNo, raisedBy, raisedByRole, type, riderCount,
                occurredAt, receivedAt, clientKey);
    }

    /** 신고 접수 시점에 함께 온 메모를 붙인다({@code ETC} 유형은 CHECK 로 이미 필수, 나머지는 선택). */
    public void attachMemo(String memo) {
        this.memo = memo;
    }

    /**
     * 신고 접수 시점의 위치를 붙인다 — 위치 캐시(Redis)에 값이 있을 때만 호출된다. 캐시가 비어 있어도
     * 신고 자체는 반드시 성공해야 하므로(안전 요구), 이 메서드를 호출하지 않고 {@code lat}·{@code lng}
     * 를 {@code null} 로 남겨 두는 것이 정상 경로다(호출부 판단, Phase 11 T2 목표 8).
     */
    public void attachLocation(BigDecimal lat, BigDecimal lng) {
        this.lat = lat;
        this.lng = lng;
    }

    /** 관계자·메인관리자의 확인 처리(EXC-04) — 최초 확인자만 기록한다({@link #isAcked} 로 중복을 막는다). */
    public void ack(Long ackedBy, OffsetDateTime ackedAt) {
        this.ackedBy = ackedBy;
        this.ackedAt = ackedAt;
    }

    /** 이미 확인 처리됐는가 — {@code 409 ALREADY_ACKED} 판정에 쓰인다. */
    public boolean isAcked() {
        return ackedAt != null;
    }

    /**
     * 발신 1분 이내 취소(EXC-04) — 행을 지우지 않고 {@code canceled_at} 을 채운다({@link
     * src.backend.run.entity.Run#cancel} 과 같은 이유: 이력 보존).
     */
    public void cancel(OffsetDateTime canceledAt) {
        this.canceledAt = canceledAt;
    }

    /** 이미 취소됐는가 — 취소 중복 처리를 막는 데 쓰인다. */
    public boolean isCanceled() {
        return canceledAt != null;
    }
}
