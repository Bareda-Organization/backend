package src.backend.schedule.entity;

import java.time.LocalTime;

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

import src.backend.global.common.BaseTimeEntity;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Weekday;

/**
 * 운행 스케줄 — 일일 회차 자동 생성의 원본이다(ERD §3.3 · SCH-01~03 · A-09).
 *
 * <p>휴원·특강 같은 예외일은 {@code run} 쪽 임시 조정으로 처리하고, 이 테이블 자체는
 * 정규 스케줄만 담아 불변으로 유지한다 — 그래서 예외일을 나타내는 컬럼이 부재하다.
 */
@Entity
@Table(name = "schedule")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Schedule extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "academy_id", nullable = false)
    private Long academyId;

    @Column(name = "bus_id", nullable = false)
    private Long busId;

    @Convert(converter = Weekday.Db.class)
    @Column(name = "weekday", length = 3, nullable = false)
    private Weekday weekday;

    @Convert(converter = Direction.Db.class)
    @Column(name = "direction", length = 20, nullable = false)
    private Direction direction;

    /** 출발 시각의 원본. 회차 생성 시 {@code service_date} 와 합쳐 {@code timestamptz} 로 확정된다. */
    @Column(name = "depart_time", nullable = false)
    private LocalTime departTime;

    @Column(name = "origin_name", length = 100, nullable = false)
    private String originName;

    @Column(name = "destination_name", length = 100, nullable = false)
    private String destinationName;

    @Column(name = "est_duration_min")
    private Integer estDurationMin;

    @Column(name = "active", nullable = false)
    private boolean active;

    private Schedule(Long academyId, Long busId, Weekday weekday, Direction direction, LocalTime departTime,
            String originName, String destinationName, Integer estDurationMin) {
        this.academyId = academyId;
        this.busId = busId;
        this.weekday = weekday;
        this.direction = direction;
        this.departTime = departTime;
        this.originName = originName;
        this.destinationName = destinationName;
        this.estDurationMin = estDurationMin;
        this.active = true;
    }

    /** 학원 관리자가 정규 운행 스케줄을 등록할 때 생성한다(SCH-01) — 활성 상태로 시작한다. */
    public static Schedule register(Long academyId, Long busId, Weekday weekday, Direction direction,
            LocalTime departTime, String originName, String destinationName, Integer estDurationMin) {
        return new Schedule(academyId, busId, weekday, direction, departTime, originName, destinationName,
                estDurationMin);
    }
}
