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

    private Route(Long academyId, RoutePlan plan) {
        this.academyId = academyId;
        this.busId = plan.busId();
        this.weekday = plan.weekday();
        this.direction = plan.direction();
        this.name = plan.name();
        this.active = plan.active() == null || plan.active();
    }

    /**
     * 학원 관리자가 요일·방향별 고정 노선 편성을 등록할 때 생성한다(RTE-01) — {@code active} 를 주지
     * 않으면 활성으로 시작한다.
     */
    public static Route register(Long academyId, RoutePlan plan) {
        return new Route(academyId, plan);
    }

    /**
     * 편성을 고친다(RTE-01, §5.9) — {@code null} 인 항목은 <b>고치지 않는다</b>는 뜻이다.
     *
     * <p>유일성 조합 셋을 고칠 수 있게 두는 것이 사양이다 — 차량 교체·요일 이동이 실제 운영의
     * 조작이라, 그 셋을 불변으로 두면 편성을 지우고 다시 만드는 것 말고 방법이 없어지고 그러면
     * 정차 순서가 FK CASCADE 로 함께 사라진다.
     */
    public void update(RoutePlan plan) {
        if (plan.busId() != null) {
            this.busId = plan.busId();
        }
        if (plan.weekday() != null) {
            this.weekday = plan.weekday();
        }
        if (plan.direction() != null) {
            this.direction = plan.direction();
        }
        if (plan.name() != null) {
            this.name = plan.name();
        }
        if (plan.active() != null) {
            this.active = plan.active();
        }
    }

    /**
     * 요청이 유일성 조합 셋 중 <b>실제로 값을 바꾸는</b> 항목을 담고 있는가 — 담지 않았으면 중복
     * 판정 대상 밖이다.
     *
     * <p>같은 값을 그대로 다시 보내는 요청을 걸러내지 않으면 자기 자신을 중복으로 세어 {@code 409}
     * 가 된다({@code Schedule#movesSlot} 과 같은 형태) — 그러면 이름만 고치려고 조합 값을 함께 보낸
     * 클라이언트가 막힌다.
     */
    public boolean movesSlot(RoutePlan plan) {
        return changes(plan.busId(), this.busId)
                || changes(plan.weekday(), this.weekday)
                || changes(plan.direction(), this.direction);
    }

    private static boolean changes(Object requested, Object current) {
        return requested != null && !requested.equals(current);
    }
}
