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

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/**
 * 변경 요청·승인 대기 — ②구간 승인 큐의 실체이자 책임 소재 기록이며, {@code source} 로 토글(즉시 반영)과
 * 일일 변경(승인 대기)을 가른다(ERD §3.4 · C-04).
 *
 * <p>{@code reject_reason}·{@code new_address} 조건부 CHECK 는 DB 가 강제한다. 상태 전이 메서드
 * ({@link #approve}·{@link #reject}·{@link #autoReject})는 전부 {@link #assertPending()} 을 거쳐
 * 이미 처리된 건의 재처리를 엔티티 단에서 막는다 — 서비스에서만 막으면 다른 호출 경로가 생길 때
 * 조용히 뚫린다.
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

    /** 아직 대기 중이 아니면 {@code 409 APPROVAL_ALREADY_DECIDED} — 세 전이 메서드가 공유하는 가드. */
    public void assertPending() {
        if (status != ChangeRequestStatus.PENDING) {
            throw new BusinessException(ErrorCode.APPROVAL_ALREADY_DECIDED);
        }
    }

    /** 관리자 승인(§9.6) — 재최적화·재배포 결과(경유지 제거 여부·적용된 노선 버전)를 함께 기록한다. */
    public void approve(Long decidedBy, OffsetDateTime decidedAt, Boolean stopRemoved, Long appliedRouteVersionId) {
        assertPending();
        this.status = ChangeRequestStatus.APPROVED;
        this.decidedBy = decidedBy;
        this.decidedAt = decidedAt;
        this.stopRemoved = stopRemoved;
        this.appliedRouteVersionId = appliedRouteVersionId;
    }

    /** 관리자 거절(§9.6) — 기존 노선을 유지하며 사유를 남긴다. */
    public void reject(Long decidedBy, OffsetDateTime decidedAt, String rejectReason) {
        assertPending();
        this.status = ChangeRequestStatus.REJECTED;
        this.decidedBy = decidedBy;
        this.decidedAt = decidedAt;
        this.rejectReason = rejectReason;
    }

    /**
     * 도래분 자동 거절(API_SPEC §1.6) — 출발 시각 도달 또는 {@code moving} 전이 중 먼저 오는 시점까지
     * 미처리로 남은 요청을 서버가 스스로 거절한다. {@code decided_by} 가 없다 — 관리자가 아니라 서버가
     * 한 일이기 때문이다. 재최적화 없이 기존 노선을 유지하고, 소진한 한도는 이 메서드가 아니라
     * {@code BoardingIntent.restoreChangeQuota()} 가 되돌린다(횟수 미소진, C-10).
     */
    public void autoReject(OffsetDateTime decidedAt) {
        assertPending();
        this.status = ChangeRequestStatus.AUTO_REJECTED;
        this.decidedAt = decidedAt;
    }
}
