package src.backend.student.entity;

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

import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Weekday;

/**
 * 요일별 등하원 주소 — 기본 주소 개념이 부재하고 요일 × 방향이 노선 산출의 유일한 기준이라
 * 조합마다 독립 행이 필요하다(ERD §3.2 · P-05 · STU-05·06 · C-12 · C-16).
 *
 * <p>{@code created_at} 이 없어({@code updated_at} 만 존재) {@code BaseTimeEntity} 를 상속하지
 * 않는다. {@code lat}/{@code lng} 는 주소 검증 전에는 값이 없어 nullable 이다(검증 실패 시
 * 저장 자체가 보류되므로 이 엔티티가 표현하는 것은 "검증 시도 전 또는 검증 대기" 상태다).
 * {@code stop_id} 도 검증 이후에만 채워지므로 이 태스크에서는 다루지 않는다.
 */
@Entity
@Table(name = "weekly_address")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WeeklyAddress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Convert(converter = Weekday.Db.class)
    @Column(name = "weekday", length = 3, nullable = false)
    private Weekday weekday;

    @Convert(converter = Direction.Db.class)
    @Column(name = "direction", length = 20, nullable = false)
    private Direction direction;

    @Column(name = "address", length = 255, nullable = false)
    private String address;

    @Column(name = "address_detail", length = 255)
    private String addressDetail;

    @Column(name = "lat", precision = 9, scale = 6)
    private BigDecimal lat;

    @Column(name = "lng", precision = 9, scale = 6)
    private BigDecimal lng;

    @Column(name = "verified", nullable = false)
    private boolean verified;

    @Column(name = "stop_id")
    private Long stopId;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    private WeeklyAddress(Long studentId, Weekday weekday, Direction direction, String address,
            String addressDetail, OffsetDateTime updatedAt) {
        this.studentId = studentId;
        this.weekday = weekday;
        this.direction = direction;
        this.address = address;
        this.addressDetail = addressDetail;
        this.verified = false;
        this.updatedAt = updatedAt;
    }

    /**
     * 학부모·학생이 요일 × 방향 조합의 주소를 등록하는 시점에 생성한다(STU-05) — 검증 전이라
     * 좌표·승하차지는 아직 없다.
     */
    public static WeeklyAddress register(Long studentId, Weekday weekday, Direction direction, String address,
            String addressDetail, OffsetDateTime updatedAt) {
        return new WeeklyAddress(studentId, weekday, direction, address, addressDetail, updatedAt);
    }
}
