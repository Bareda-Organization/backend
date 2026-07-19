package src.backend.schedule.entity;

import java.time.LocalDate;
import java.time.LocalTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import src.backend.global.common.ApprovalStatus;
import src.backend.global.common.BaseTimeEntity;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/**
 * 등하원 시간 변경 요청. 승인되면 해당 날짜 배차·명단에 반영한다(Phase 6 routing이 소비).
 */
@Entity
@Table(name = "schedule_change_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScheduleChangeRequest extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long tenantId;

    @Column(nullable = false)
    private Long studentId;

    @Column(nullable = false)
    private LocalDate requestedDate;

    @Column(nullable = false)
    private LocalTime requestedTime;

    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApprovalStatus status;

    private Long processedBy;   // 처리한 관리자 User id

    @Builder
    public ScheduleChangeRequest(Long tenantId, Long studentId, LocalDate requestedDate,
                                 LocalTime requestedTime, String reason) {
        this.tenantId = tenantId;
        this.studentId = studentId;
        this.requestedDate = requestedDate;
        this.requestedTime = requestedTime;
        this.reason = reason;
        this.status = ApprovalStatus.PENDING;
    }

    /** 관리자 승인(PENDING → APPROVED). */
    public void approve(Long adminUserId) {
        if (status != ApprovalStatus.PENDING) {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 처리된 요청입니다");
        }
        this.status = ApprovalStatus.APPROVED;
        this.processedBy = adminUserId;
    }

    /** 관리자 반려(PENDING → REJECTED). */
    public void reject(Long adminUserId) {
        if (status != ApprovalStatus.PENDING) {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 처리된 요청입니다");
        }
        this.status = ApprovalStatus.REJECTED;
        this.processedBy = adminUserId;
    }
}
