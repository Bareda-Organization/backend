package src.backend.location.entity;

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
 * 운행 중 버스 위치 — 송신 주기가 5~10초라 한 회차에 수백 행이 쌓이는 최대 적재 테이블이다
 * (ERD §3.6 · ARCHITECTURE §7~8).
 *
 * <p>{@code run} 은 논리적 부모이나 DB FK 가 미설정이다(ERD §4.2 — 파티션 단위 DROP 으로 정리해야 해서
 * 원본과 보존 주기를 분리한다). {@code recorded_at}(기기 시각)·{@code received_at}(서버 수신 시각) 은
 * 서로 다른 두 시계이므로 팩토리 파라미터로 각각 받는다 — 위도·경도는 CHECK 경계(±90/±180) 위반을
 * 막기 위해 {@code double} 이 아닌 {@link BigDecimal} 로 받는다(Ruling 34).
 */
@Entity
@Table(name = "run_position")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RunPosition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "lat", precision = 9, scale = 6, nullable = false)
    private BigDecimal lat;

    @Column(name = "lng", precision = 9, scale = 6, nullable = false)
    private BigDecimal lng;

    @Column(name = "recorded_at", nullable = false)
    private OffsetDateTime recordedAt;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "speed", precision = 5, scale = 2)
    private BigDecimal speed;

    @Column(name = "heading", precision = 5, scale = 2)
    private BigDecimal heading;

    private RunPosition(Long runId, BigDecimal lat, BigDecimal lng, OffsetDateTime recordedAt,
            OffsetDateTime receivedAt) {
        this.runId = runId;
        this.lat = lat;
        this.lng = lng;
        this.recordedAt = recordedAt;
        this.receivedAt = receivedAt;
    }

    /** 기사 단말이 위치를 송신해 서버가 수신했을 때 생성한다 — 속도·방위는 선택값이라 팩토리가 다루지 않는다. */
    public static RunPosition onReceive(Long runId, BigDecimal lat, BigDecimal lng,
            OffsetDateTime recordedAt, OffsetDateTime receivedAt) {
        return new RunPosition(runId, lat, lng, recordedAt, receivedAt);
    }
}
