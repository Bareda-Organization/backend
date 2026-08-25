package src.backend.routing.entity;

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
 * 고정 노선 — 학기 단위로 유지되는 편성이다(ERD §3.3 · RTE-01 · A-08 · P-08).
 *
 * <p>확정 노선({@code confirmed_route}/{@code route_version})과 별개 레코드로 두어, 당일 변경이
 * 이 원본을 오염시키지 않게 분리한다. 확정 전 학부모·기사 화면이 표시하는 "확정 전" 노선의 출처다.
 */
@Entity
@Table(name = "route")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Route extends BaseTimeEntity {

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

    @Column(name = "name", length = 100)
    private String name;

    @Column(name = "active", nullable = false)
    private boolean active;

    private Route(Long academyId, Long busId, Weekday weekday, Direction direction, String name) {
        this.academyId = academyId;
        this.busId = busId;
        this.weekday = weekday;
        this.direction = direction;
        this.name = name;
        this.active = true;
    }

    /** 학원 관리자가 요일·방향별 고정 노선 편성을 등록할 때 생성한다(RTE-01) — 활성 상태로 시작한다. */
    public static Route register(Long academyId, Long busId, Weekday weekday, Direction direction, String name) {
        return new Route(academyId, busId, weekday, direction, name);
    }
}
