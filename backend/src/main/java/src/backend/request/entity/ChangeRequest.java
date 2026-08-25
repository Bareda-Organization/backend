package src.backend.request.entity;

import java.math.BigDecimal;
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
 * 변경 요청·승인 대기 — ②구간 승인 큐의 실체이자 책임 소재 기록이며, {@code source} 로 토글(즉시 반영)과
 * 일일 변경(승인 대기)을 가른다(ERD §3.4 · C-04).
 *
 * <p>{@code reject_reason}·{@code new_address} 조건부 CHECK 는 DB 가 강제하며, 승인/반려 판정 메서드는
 * 이 태스크 범위 밖이다(Phase 8 담당) — 팩토리는 접수 시점 상태({@code PENDING})까지만 채운다.
 * 생성 시각·마감 시각 등 업무 타임스탬프가 호출자 입력이라 별도 감사 컬럼({@code created_at} 등)이 없다.
 */
@Entity
@Table(name = "change_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChangeRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "academy_id", nullable = false)
    private Long academyId;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Convert(converter = ChangeRequestSource.Db.class)
    @Column(name = "source", length = 20, nullable = false)
    private ChangeRequestSource source;

    @Convert(converter = ChangeRequestType.Db.class)
    @Column(name = "type", length = 10, nullable = false)
    private ChangeRequestType type;

    @Convert(converter = ChangeRequestStatus.Db.class)
    @Column(name = "status", length = 15, nullable = false)
    private ChangeRequestStatus status;

    @Column(name = "window_segment", nullable = false)
    private Short windowSegment;

    @Column(name = "new_address")
    private String newAddress;

    @Column(name = "new_lat", precision = 9, scale = 6)
    private BigDecimal newLat;

    @Column(name = "new_lng", precision = 9, scale = 6)
    private BigDecimal newLng;

    @Column(name = "new_stop_id")
    private Long newStopId;

    @Column(name = "reason", length = 200)
    private String reason;

    @Column(name = "requested_by", nullable = false)
    private Long requestedBy;

    @Column(name = "requested_at", nullable = false)
    private OffsetDateTime requestedAt;

    @Column(name = "deadline_at")
    private OffsetDateTime deadlineAt;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @Column(name = "reject_reason", length = 200)
    private String rejectReason;

    @Column(name = "stop_removed")
    private Boolean stopRemoved;

    @Column(name = "applied_route_version_id")
    private Long appliedRouteVersionId;

    private ChangeRequest(Long academyId, Long runId, Long studentId, ChangeRequestSource source,
            ChangeRequestType type, Short windowSegment, Long requestedBy, OffsetDateTime requestedAt) {
        this.academyId = academyId;
        this.runId = runId;
        this.studentId = studentId;
        this.source = source;
        this.type = type;
        this.status = ChangeRequestStatus.PENDING;
        this.windowSegment = windowSegment;
        this.requestedBy = requestedBy;
        this.requestedAt = requestedAt;
    }

    /**
     * 학부모(또는 시스템)가 변경을 접수한 시점의 최소 상태로 생성한다 — 상태는 항상 대기중에서 시작한다.
     * 파라미터 8개는 §20.2 기준(4개)을 넘지만 NN 필드 전부가 접수 시점에 필요해 규약 §3(팩토리 분화 금지)을 우선했다.
     */
    public static ChangeRequest forRequest(Long academyId, Long runId, Long studentId,
            ChangeRequestSource source, ChangeRequestType type, Short windowSegment,
            Long requestedBy, OffsetDateTime requestedAt) {
        return new ChangeRequest(academyId, runId, studentId, source, type, windowSegment,
                requestedBy, requestedAt);
    }
}
