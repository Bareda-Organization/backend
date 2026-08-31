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

    /**
     * 확정 배치가 이 회차에서 연속으로 실패한 횟수(Phase 7 목표 4) — 성공하면 0으로 되돌아간다.
     *
     * <p>값을 이 컬럼에 두는 이유는 배치가 재시작되면 인메모리 카운터는 사라지기 때문이다. 이 필드는
     * 읽기 전용이다 — 증가·초기화는 {@code RunRepository} 의 조건부 UPDATE 가 DB 에서 직접 하고,
     * 엔티티를 통해 쓰지 않는다(아래 {@code confirmIfIdle}·{@code recordFailure} 참고). 조건부 UPDATE 인
     * 이유는 확정 배치의 멱등성(목표 2)이 영향받은 행 수로 판정되어야 하는데, 엔티티 변경 후
     * {@code save()} 로는 "내가 실제로 idle 이던 행을 바꿨는지" 를 알 수 없기 때문이다.
     */
    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

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

    /**
     * 기사의 운행 시작 처리로 {@code moving} 에 진입한다(API_SPEC §4.4, RUN-02·M-10).
     *
     * <p>±10분 창 판정·중복 시작 차단은 이 메서드가 아니라 호출부(커맨드 서비스)의 책임이다 —
     * 엔티티는 "그 시각에 시작했다"는 사실만 기록하고, 그 시각이 유효한지는 판단하지 않는다.
     */
    public void start(OffsetDateTime startedAt) {
        this.status = RunStatus.MOVING;
        this.startedAt = startedAt;
    }

    /**
     * 하원 최종 지점 도착 처리에서 미하차 잔류가 있어 종료를 보류한다(API_SPEC §4.5, C-15).
     *
     * <p>{@code status} 는 여전히 {@link RunStatus#MOVING} 이다 — 종료는 잔류 인원이 0이 되는
     * 순간 {@link #finish} 로만 이뤄진다({@link src.backend.run.command.RunCompletionService}).
     */
    public void deferFinish() {
        this.finishPending = true;
    }

    /**
     * 운행을 종료한다(C-15) — 등원 최종 도착 처리의 즉시 종료, 하원의 잔류 0명 도달(도착 즉시 또는
     * 마지막 탑승자 하차 시점) 양쪽 모두 이 메서드로 수렴한다.
     *
     * <p>{@code finishPending} 을 함께 거둔다 — 보류 중이던 종료가 지금 이뤄지는 것이므로 보류
     * 표시가 남아 있으면 안 된다.
     */
    public void finish(OffsetDateTime finishedAt) {
        this.status = RunStatus.FINISHED;
        this.finishedAt = finishedAt;
        this.finishPending = false;
    }
}
