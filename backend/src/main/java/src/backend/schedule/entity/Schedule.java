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

    private Schedule(Long academyId, SchedulePlan plan) {
        this.academyId = academyId;
        this.busId = plan.busId();
        this.weekday = plan.weekday();
        this.direction = plan.direction();
        this.departTime = plan.departTime();
        this.originName = plan.originName();
        this.destinationName = plan.destinationName();
        this.estDurationMin = plan.estDurationMin();
        this.active = plan.active() == null || plan.active();
    }

    /**
     * 학원 관리자가 정규 운행 스케줄을 등록할 때 생성한다(SCH-01) — {@code active} 를 주지 않으면
     * 활성으로 시작한다.
     */
    public static Schedule register(Long academyId, SchedulePlan plan) {
        return new Schedule(academyId, plan);
    }

    /**
     * 스케줄을 고친다(SCH-01, §5.10) — {@code null} 인 항목은 <b>고치지 않는다</b>는 뜻이다.
     *
     * <p>유일성 조합 넷을 고칠 수 있게 두는 것이 사양이다(§5.10) — 출발 시각만 옮기는 것이 실제
     * 운영의 조작이라, 그 넷을 불변으로 두면 스케줄을 지우고 다시 만드는 것 말고 방법이 없어진다.
     * 그러면 이미 만들어진 회차의 {@code schedule_id} 가 함께 끊긴다.
     */
    public void update(SchedulePlan plan) {
        if (plan.busId() != null) {
            this.busId = plan.busId();
        }
        if (plan.weekday() != null) {
            this.weekday = plan.weekday();
        }
        if (plan.direction() != null) {
            this.direction = plan.direction();
        }
        if (plan.departTime() != null) {
            this.departTime = plan.departTime();
        }
        if (plan.originName() != null) {
            this.originName = plan.originName();
        }
        if (plan.destinationName() != null) {
            this.destinationName = plan.destinationName();
        }
        if (plan.estDurationMin() != null) {
            this.estDurationMin = plan.estDurationMin();
        }
        if (plan.active() != null) {
            this.active = plan.active();
        }
    }

    /**
     * 요청이 유일성 조합 넷 중 <b>실제로 값을 바꾸는</b> 항목을 담고 있는가 — 담지 않았으면 중복
     * 판정 대상 밖이다.
     *
     * <p>같은 값을 그대로 다시 보내는 요청을 걸러내지 않으면 자기 자신을 중복으로 세어 {@code 409}
     * 가 된다({@code BusCommandService.renamesBusNo} 와 같은 형태) — 그러면 출발지 이름만 고치려고
     * 조합 값을 함께 보낸 클라이언트가 막힌다.
     */
    public boolean movesSlot(SchedulePlan plan) {
        return changes(plan.busId(), this.busId)
                || changes(plan.weekday(), this.weekday)
                || changes(plan.direction(), this.direction)
                || changes(plan.departTime(), this.departTime);
    }

    private static boolean changes(Object requested, Object current) {
        return requested != null && !requested.equals(current);
    }
}
