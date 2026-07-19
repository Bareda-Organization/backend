package src.backend.attendance.entity;

import java.time.LocalDate;

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
 * 결석·휴원 신고. 승인되면 해당 날짜 노선 배정에서 학생을 스킵(명단 자동 갱신)한다.
 */
@Entity
@Table(name = "attendance_exception")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AttendanceException extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long tenantId;

    @Column(nullable = false)
    private Long studentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AttendanceType type;

    @Column(nullable = false)
    private LocalDate targetDate;

    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApprovalStatus status;

    private Long processedBy;   // 처리한 관리자 User id

    @Builder
    public AttendanceException(Long tenantId, Long studentId, AttendanceType type,
                               LocalDate targetDate, String reason) {
        this.tenantId = tenantId;
        this.studentId = studentId;
        this.type = type;
        this.targetDate = targetDate;
        this.reason = reason;
        this.status = ApprovalStatus.PENDING;
    }

    /** 관리자 승인(PENDING → APPROVED). 이후 당일 명단 스킵 대상이 된다. */
    public void approve(Long adminUserId) {
        if (status != ApprovalStatus.PENDING) {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 처리된 신청입니다");
        }
        this.status = ApprovalStatus.APPROVED;
        this.processedBy = adminUserId;
    }

    /** 관리자 반려(PENDING → REJECTED). */
    public void reject(Long adminUserId) {
        if (status != ApprovalStatus.PENDING) {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 처리된 신청입니다");
        }
        this.status = ApprovalStatus.REJECTED;
        this.processedBy = adminUserId;
    }
}
