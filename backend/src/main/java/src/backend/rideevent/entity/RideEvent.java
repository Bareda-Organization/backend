package src.backend.rideevent.entity;

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

/**
 * 승하차 기록 — 하나를 4개 역할이 각자 권한 범위에서 조회하는 핵심 이벤트.
 * 대용량 테이블이라 연관 엔티티 대신 식별자(Long)로 참조하고 tenant_id 로 격리한다.
 * 정정(CORRECTION)은 원본을 덮어쓰지 않고 correctedBy/originalRef 를 채운 새 행으로 남긴다.
 */
@Entity
@Table(name = "ride_event",
        indexes = @Index(name = "idx_ride_tenant_student", columnList = "tenantId, studentId, occurredAt"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RideEvent extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long tenantId;

    @Column(nullable = false)
    private Long studentId;

    @Column(nullable = false)
    private Long busId;

    private Long stopId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RideType type;

    @Column(nullable = false)
    private LocalDateTime occurredAt;

    private Double lat;
    private Double lng;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RideSource source;

    // 정정 이력 필드 (기사 체크 누락 정정 대응)
    private Long correctedBy;
    private LocalDateTime correctedAt;
    private Long originalRef;

    @Builder
    public RideEvent(Long tenantId, Long studentId, Long busId, Long stopId, RideType type,
                     LocalDateTime occurredAt, Double lat, Double lng, RideSource source,
                     Long correctedBy, LocalDateTime correctedAt, Long originalRef) {
        this.tenantId = tenantId;
        this.studentId = studentId;
        this.busId = busId;
        this.stopId = stopId;
        this.type = type;
        this.occurredAt = occurredAt;
        this.lat = lat;
        this.lng = lng;
        this.source = source;
        this.correctedBy = correctedBy;
        this.correctedAt = correctedAt;
        this.originalRef = originalRef;
    }
}
