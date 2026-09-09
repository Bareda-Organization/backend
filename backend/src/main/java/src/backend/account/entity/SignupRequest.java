package src.backend.account.entity;

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

import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/**
 * 회원가입 승인 요청 — 계정 생성과 활성화를 분리하는 승인 큐다. 재신청은 새 행을 쌓아 처리
 * 이력을 남긴다(ERD §3.1 · AUTH-01·03·10 · ACAD-05 · C-01).
 *
 * <p>{@code created_at}/{@code updated_at} 쌍이 없어 {@code BaseTimeEntity} 를 상속하지 않는다
 * ({@code requested_at} 만 존재). {@code decided_by}/{@code decided_at}/{@code reject_reason} 은
 * {@link #accept}/{@link #reject} 가 채운다.
 */
@Entity
@Table(name = "signup_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SignupRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "academy_id", nullable = false)
    private Long academyId;

    /**
     * 신청 역할 — 이 컬럼에는 DB CHECK 가 부재해 스키마가 값을 보장하지 않는다(Ruling 55).
     * 잘못된 값은 쓸 때가 아니라 다시 읽을 때 {@link Role.Db#convertToEntityAttribute} 의
     * {@link Enum#valueOf} 에서 실패한다.
     */
    @Convert(converter = Role.Db.class)
    @Column(name = "requested_role", length = 20, nullable = false)
    private Role requestedRole;

    @Convert(converter = ApproverType.Db.class)
    @Column(name = "approver_type", length = 20, nullable = false)
    private ApproverType approverType;

    @Convert(converter = SignupRequestStatus.Db.class)
    @Column(name = "status", length = 10, nullable = false)
    private SignupRequestStatus status;

    @Column(name = "requested_at", nullable = false)
    private OffsetDateTime requestedAt;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @Column(name = "reject_reason", length = 200)
    private String rejectReason;

    private SignupRequest(Long academyId, Long accountId, Role requestedRole, ApproverType approverType,
            OffsetDateTime requestedAt) {
        this.academyId = academyId;
        this.accountId = accountId;
        this.requestedRole = requestedRole;
        this.approverType = approverType;
        this.requestedAt = requestedAt;
        this.status = SignupRequestStatus.PENDING;
    }

    /** 계정이 가입 form 을 제출한 시점에 생성한다(AUTH-01) — 대기 상태로 시작한다. */
    public static SignupRequest uponSubmission(Long academyId, Long accountId, Role requestedRole,
            ApproverType approverType, OffsetDateTime requestedAt) {
        return new SignupRequest(academyId, accountId, requestedRole, approverType, requestedAt);
    }

    /**
     * 아직 처리되지 않은 건인지 확인한다 — 아니면 {@code 409 APPROVAL_ALREADY_DECIDED}(API_SPEC §5.2·§6.5).
     *
     * <p>{@link #accept}/{@link #reject} 안에도 같은 확인이 있지만 승인 서비스가 <b>먼저</b> 부른다 —
     * 수락 경로는 마감 전에 레코드 연결·정원 판정을 하므로, 여기서 걸러 두지 않으면 이미 처리된 건에
     * 대해 {@code 422 LINK_REQUIRED} 같은 다른 코드가 먼저 나가 원인이 뒤바뀐다.
     */
    public void assertPending() {
        if (status != SignupRequestStatus.PENDING) {
            throw new BusinessException(ErrorCode.APPROVAL_ALREADY_DECIDED);
        }
    }

    /** 수락으로 마감한다(AUTH-10 · ACAD-05) — 처리자·일시를 함께 적재해 승인 이력을 남긴다. */
    public void accept(Long decidedBy, OffsetDateTime decidedAt) {
        assertPending();
        this.status = SignupRequestStatus.ACCEPTED;
        this.decidedBy = decidedBy;
        this.decidedAt = decidedAt;
    }

    /**
     * 거절로 마감한다(AUTH-10 · ACAD-05) — 사유는 대기 화면이 그대로 보여 주므로(API_SPEC §1.4)
     * 호출자가 비워 둘 수 없다.
     */
    public void reject(Long decidedBy, OffsetDateTime decidedAt, String rejectReason) {
        assertPending();
        this.status = SignupRequestStatus.REJECTED;
        this.decidedBy = decidedBy;
        this.decidedAt = decidedAt;
        this.rejectReason = rejectReason;
    }
}
