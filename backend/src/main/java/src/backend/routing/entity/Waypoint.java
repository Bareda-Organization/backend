package src.backend.routing.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 강제 경유 지점 — 학생 주소로 표현되지 않는 경유 요구(학원 사정 · 도로 통제 · 임시 집결지)를
 * 담는 단위다(ERD §3.3 · RTE-10 · A-15 · API_SPEC §5.15). 강제 추가(학생 단위)와 달리
 * 탑승자 없이 경유만 필요한 경우가 대상이다.
 *
 * <p>{@code created_at} 만 있고 {@code updated_at} 이 없어 {@code BaseTimeEntity} 를 상속하지
 * 않는다 — {@code createdAt} 은 호출자가 직접 넣는다.
 */
@Entity
@Table(name = "waypoint")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Waypoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "label", length = 100, nullable = false)
    private String label;

    @Column(name = "address", length = 255)
    private String address;

    @Column(name = "lat", nullable = false, precision = 9, scale = 6)
    private BigDecimal lat;

    @Column(name = "lng", nullable = false, precision = 9, scale = 6)
    private BigDecimal lng;

    @Column(name = "note", length = 200)
    private String note;

    /** 배포 완료 여부. {@code false} = 미리보기 단계. */
    @Column(name = "applied", nullable = false)
    private boolean applied;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "removed_at")
    private OffsetDateTime removedAt;

    private Waypoint(Long runId, String label, String address, BigDecimal lat, BigDecimal lng, String note,
            Long createdBy, OffsetDateTime createdAt) {
        this.runId = runId;
        this.label = label;
        this.address = address;
        this.lat = lat;
        this.lng = lng;
        this.note = note;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.applied = false;
    }

    /** 관계자가 회차에 강제 경유지를 지정할 때 생성한다(RTE-10) — 아직 배포 전(미리보기) 상태로 시작한다. */
    public static Waypoint forRun(Long runId, String label, String address, BigDecimal lat, BigDecimal lng,
            String note, Long createdBy, OffsetDateTime createdAt) {
        return new Waypoint(runId, label, address, lat, lng, note, createdBy, createdAt);
    }
}
