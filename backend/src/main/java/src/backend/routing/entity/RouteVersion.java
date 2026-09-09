package src.backend.routing.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
 * 확정 노선 배포 버전 — ②구간 승인·경유 지점 지정이 확정 노선을 재최적화 후 재배포하므로, 배포 단위를
 * 식별할 레코드가 필요하다(ERD §3.3 · A-05 · A-15 · REQ-04 · RTE-10 · API_SPEC §5.6·§5.15).
 *
 * <p>{@code created_at} 만 있고 {@code updated_at} 이 없어 {@code BaseTimeEntity} 를 상속하지
 * 않는다 — 배포 버전은 불변 레코드라 수정 시각 개념이 없다. {@code createdAt} 은 호출자가 직접 넣는다.
 *
 * <p>{@code confirmedRouteId} 는 {@code confirmed_route.current_version_id} 와 순환 FK 쌍인
 * {@code confirmed_route.run_id} 를 가리킨다 — 엔티티 참조 없이 {@code Long} 컬럼으로만 잇는다.
 */
@Entity
@Table(name = "route_version")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RouteVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "confirmed_route_id", nullable = false)
    private Long confirmedRouteId;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Convert(converter = RouteVersionSource.Db.class)
    @Column(name = "source", length = 20, nullable = false)
    private RouteVersionSource source;

    @Column(name = "est_duration_min")
    private Integer estDurationMin;

    @Column(name = "est_distance_km", precision = 6, scale = 2)
    private BigDecimal estDistanceKm;

    /** 배포 시각. {@code null} 이면 미리보기 전용 버전(배포되지 않음). */
    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    @Column(name = "input_fingerprint", length = 64, nullable = false)
    private String inputFingerprint;

    @Column(name = "engine_name", length = 30, nullable = false)
    private String engineName;

    /** 산출 시점 정책값 — 정책이 바뀌어도 과거 노선을 재현·설명할 수 있게 스냅샷을 그대로 담는다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "policy_snapshot", nullable = false)
    private Map<String, Object> policySnapshot;

    @Column(name = "fallback_used", nullable = false)
    private boolean fallbackUsed;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    private RouteVersion(Long confirmedRouteId, int versionNo, RouteVersionSource source, Integer estDurationMin,
            BigDecimal estDistanceKm, OffsetDateTime publishedAt, String inputFingerprint, String engineName,
            Map<String, Object> policySnapshot, boolean fallbackUsed, Long createdBy, OffsetDateTime createdAt) {
        this.confirmedRouteId = confirmedRouteId;
        this.versionNo = versionNo;
        this.source = source;
        this.estDurationMin = estDurationMin;
        this.estDistanceKm = estDistanceKm;
        this.publishedAt = publishedAt;
        this.inputFingerprint = inputFingerprint;
        this.engineName = engineName;
        this.policySnapshot = policySnapshot;
        this.fallbackUsed = fallbackUsed;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    /** 재최적화 엔진이 새 노선 버전을 산출할 때 생성한다(RTE-10) — 미리보기·배포 여부는 {@code publishedAt} 으로 구분된다. */
    public static RouteVersion forConfirmedRoute(Long confirmedRouteId, int versionNo, RouteVersionSource source,
            Integer estDurationMin, BigDecimal estDistanceKm, OffsetDateTime publishedAt, String inputFingerprint,
            String engineName, Map<String, Object> policySnapshot, boolean fallbackUsed, Long createdBy,
            OffsetDateTime createdAt) {
        return new RouteVersion(confirmedRouteId, versionNo, source, estDurationMin, estDistanceKm, publishedAt,
                inputFingerprint, engineName, policySnapshot, fallbackUsed, createdBy, createdAt);
    }
}
