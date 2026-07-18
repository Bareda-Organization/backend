package src.backend.sos.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import src.backend.global.common.BaseTimeEntity;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/**
 * 학생 긴급 SOS 이벤트. 단방향 상태 전이(OPEN → ACKNOWLEDGED → RESOLVED)를 따른다.
 * 발신 즉시 학부모+관리자에게 알리고, 3분간 관리자 확인(ACKNOWLEDGED)이 없으면
 * {@code SosEscalationScheduler}가 플랫폼관리자에게 에스컬레이션한다.
 */
@Entity
@Table(name = "sos_event",
        indexes = @Index(name = "idx_sos_status_occurred", columnList = "status, occurredAt"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SosEvent extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long tenantId;

    @Column(nullable = false)
    private Long studentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SosStatus status;

    private Double lat;
    private Double lng;

    @Column(nullable = false)
    private LocalDateTime occurredAt;

    private Long acknowledgedBy;
    private LocalDateTime acknowledgedAt;
    private Long resolvedBy;
    private LocalDateTime resolvedAt;

    @Builder
    public SosEvent(Long tenantId, Long studentId, Double lat, Double lng, LocalDateTime occurredAt) {
        this.tenantId = tenantId;
        this.studentId = studentId;
        this.status = SosStatus.OPEN;
        this.lat = lat;
        this.lng = lng;
        this.occurredAt = occurredAt;
    }

    /** 관리자 확인(OPEN → ACKNOWLEDGED). 에스컬레이션 스케줄러가 더는 대상으로 삼지 않게 된다. */
    public void acknowledge(Long adminUserId) {
        if (status != SosStatus.OPEN) {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 확인된 SOS 입니다");
        }
        this.status = SosStatus.ACKNOWLEDGED;
        this.acknowledgedBy = adminUserId;
        this.acknowledgedAt = LocalDateTime.now();
    }

    /** 상황 종료(ACKNOWLEDGED → RESOLVED). 확인 전 종료는 허용하지 않는다(누가 대응했는지 반드시 남긴다). */
    public void resolve(Long adminUserId) {
        if (status != SosStatus.ACKNOWLEDGED) {
            throw new BusinessException(ErrorCode.CONFLICT, "확인되지 않은 SOS 는 종료할 수 없습니다");
        }
        this.status = SosStatus.RESOLVED;
        this.resolvedBy = adminUserId;
        this.resolvedAt = LocalDateTime.now();
    }
}
