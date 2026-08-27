package src.backend.run.entity;

import java.time.LocalDate;
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

import src.backend.global.common.BaseTimeEntity;
import src.backend.global.common.enums.Direction;

/**
 * 일일 운행 회차 — 3구간 판정 · 확정 배치 · 명단 · 위치 · 알림이 전부 매달리는 중심 축이다
 * (ERD §3.3 · SCH-02 · RTE-02 · RUN-02·04·05 · C-04 · C-15). 소유 모듈은 {@code run} 이다
 * (`ARCHITECTURE §3.3` — {@code schedule} 이 생성 원본을 두고 {@code routing} 이 확정 전이하지만
 * 소유는 {@code run}).
 *
 * <p>{@code confirmAt} 은 {@code depart_time - 30분} 이라는 DB CHECK({@code ck_run_confirm_at})의
 * 대상이지만, 그 계산은 이 태스크의 범위 밖(Phase 7)이라 팩토리는 호출자가 미리 계산해 넘긴 값을
 * 그대로 저장한다 — 정책값 {@code 30} 을 이 코드에 박아 넣지 않기 위함이다.
 */
@Entity
@Table(name = "run")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Run extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "academy_id", nullable = false)
    private Long academyId;

    @Column(name = "bus_id", nullable = false)
    private Long busId;

    /** 생성 원본 스케줄. 관리자가 임시로 추가한 회차(SCH-03)는 {@code null}. */
    @Column(name = "schedule_id")
    private Long scheduleId;

    @Column(name = "service_date", nullable = false)
    private LocalDate serviceDate;

    @Convert(converter = Direction.Db.class)
    @Column(name = "direction", length = 20, nullable = false)
    private Direction direction;

    /** 출발 시각 — 3구간 판정의 기준. */
    @Column(name = "depart_time", nullable = false)
    private OffsetDateTime departTime;

    /** 확정 예정 시각 = {@code depart_time - 30분}. 확정 배치의 조회 대상 판정에 쓰인다. */
    @Column(name = "confirm_at", nullable = false)
    private OffsetDateTime confirmAt;

    @Convert(converter = RunStatus.Db.class)
    @Column(name = "status", length = 10, nullable = false)
    private RunStatus status;

    @Column(name = "origin_name", length = 100, nullable = false)
    private String originName;

    @Column(name = "destination_name", length = 100, nullable = false)
    private String destinationName;

    @Column(name = "est_duration_min")
    private Integer estDurationMin;

    /** 확정 배치가 실제로 실행된 시각. 지연 실행 추적 근거(PRD §5.2). */
    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    /** 하원 최종 도착 처리 후 미하차 잔류로 종료가 보류 중인지. */
    @Column(name = "finish_pending", nullable = false)
    private boolean finishPending;

    @Column(name = "canceled_at")
    private OffsetDateTime canceledAt;

    private Run(Long academyId, Long busId, Long scheduleId, LocalDate serviceDate, Direction direction,
            OffsetDateTime departTime, OffsetDateTime confirmAt, String originName, String destinationName,
            Integer estDurationMin) {
        this.academyId = academyId;
        this.busId = busId;
        this.scheduleId = scheduleId;
        this.serviceDate = serviceDate;
        this.direction = direction;
        this.departTime = departTime;
        this.confirmAt = confirmAt;
        this.status = RunStatus.IDLE;
        this.originName = originName;
        this.destinationName = destinationName;
        this.estDurationMin = estDurationMin;
        this.finishPending = false;
    }

    /**
     * 일일 회차 생성 배치가 스케줄로부터 만들거나, 관리자가 임시 회차를 추가할 때({@code scheduleId}
     * 가 {@code null}) 생성한다(SCH-02·03) — 확정 전 대기 상태({@link RunStatus#IDLE})로 시작한다.
     */
    public static Run forSchedule(Long academyId, Long busId, Long scheduleId, LocalDate serviceDate,
            Direction direction, OffsetDateTime departTime, OffsetDateTime confirmAt, String originName,
            String destinationName, Integer estDurationMin) {
        return new Run(academyId, busId, scheduleId, serviceDate, direction, departTime, confirmAt, originName,
                destinationName, estDurationMin);
    }

    /**
     * 특정일 회차를 임시로 취소한다(SCH-03, API_SPEC §5.10) — 행을 지우지 않고 {@code canceledAt} 을
     * 채운다.
     *
     * <p>행을 남기는 이유는 정규 스케줄이 불변이기 때문이다 — 휴원·특강 같은 예외일은 <b>그날의
     * 회차에만</b> 표시되어야 하고, 지우면 "오늘은 쉬기로 했다" 와 "회차가 아직 안 만들어졌다" 가
     * 구별되지 않는다. 다음 배치가 지워진 회차를 그대로 다시 만들기까지 한다.
     *
     * <p>시각을 파라미터로 받는다(횡단 규칙 1) — 호출부가 주입된 {@code Clock} 에서 얻어 넘긴다.
     */
    public void cancel(OffsetDateTime canceledAt) {
        this.canceledAt = canceledAt;
    }

    /** 이미 취소된 회차인가 — 배치 충돌 판정(MGR-06)이 이 회차를 세지 않는다. */
    public boolean isCanceled() {
        return canceledAt != null;
    }
}
