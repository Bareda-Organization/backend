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

/**
 * 회원가입 승인 요청 — 계정 생성과 활성화를 분리하는 승인 큐다. 재신청은 새 행을 쌓아 처리
 * 이력을 남긴다(ERD §3.1 · AUTH-01·03·10 · ACAD-05 · C-01).
 *
 * <p>{@code created_at}/{@code updated_at} 쌍이 없어 {@code BaseTimeEntity} 를 상속하지 않는다
 * ({@code requested_at} 만 존재). Phase 1 은 필드 매핑까지이므로 승인·거절 시점에 채워지는
 * {@code decided_by}/{@code decided_at}/{@code reject_reason} 은 이 태스크에서 값을 넣지 않는다.
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
}
