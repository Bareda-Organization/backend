package src.backend.run.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.request.ApiValues;
import src.backend.global.security.AuthUser;
import src.backend.run.domain.RunConfirmationPolicy;
import src.backend.run.dto.RunCreateRequest;
import src.backend.run.dto.RunResponse;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunDraft;
import src.backend.run.repository.RunRepository;

/**
 * 회차 생성·임시 취소(SCH-02·03, API_SPEC §5.10) — {@code run} 테이블을 쓰는 유일한 쓰기 지점이다
 * (ARCHITECTURE §3.3: 소유는 {@code run} 모듈이고 {@code schedule} 은 이 메서드를 거쳐서만 만든다).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class RunCommandService {

    /**
     * 회차 유일성을 강제하는 제약 이름({@code V1__init_schema.sql}).
     *
     * <p>이름으로 가리는 이유는 {@code run} 에 CHECK 가 셋 더 있기 때문이다
     * ({@code ck_run_direction}·{@code ck_run_status}·{@code ck_run_confirm_at}). 제약을 가리지 않고
     * {@code DataIntegrityViolationException} 을 통째로 409 로 옮기면 <b>확정 시각 계산이 틀린 것</b>
     * 까지 "이미 등록된 회차입니다" 로 답해, 정책이 어긋난 것을 중복으로 오인하게 된다.
     */
    private static final String RUN_SLOT_UNIQUE_CONSTRAINT = "uk_run_bus_date_direction_depart";

    private final RunRepository runRepository;

    private final BusRepository busRepository;

    private final Clock clock;

    /**
     * 특정일 회차를 임시로 추가한다(SCH-03, §5.10) — 만들어진 회차는 {@code schedule_id} 가 비어 있다.
     *
     * <p>중복은 여기서 {@code 409 DUPLICATE_RUN} 이다. <b>같은 제약이 배치에서는 오류가 아니다</b>
     * ({@link #create}) — 관계자가 손으로 같은 회차를 두 번 넣는 것은 알려 줘야 할 실수이고, 배치가
     * 두 번 도는 것은 정상 동작이다.
     */
    public RunResponse add(AuthUser requester, RunCreateRequest request) {
        Bus bus = busRepository.findByIdAndAcademyId(request.busId(), requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.BUS_NOT_FOUND));
        Run run = create(new RunDraft(requester.academyId(), request.busId(), null,
                ApiValues.date(request.serviceDate()), ApiValues.direction(request.direction()),
                ApiValues.time(request.departTime()), request.originName(), request.destinationName(),
                request.estDurationMin()));
        return RunResponse.of(run, bus.getBusNo(), List.of());
    }

    /**
     * 회차 1건을 만든다 — 이미 있으면 {@code 409 DUPLICATE_RUN} 을 던진다.
     *
     * <p><b>선검사만으로는 부족하다.</b> 배치가 동시에 두 번 돌면 두 트랜잭션은 서로의 미커밋 INSERT 를
     * 보지 못한 채 둘 다 선검사를 지나고, 그 뒤 {@code uk_run_bus_date_direction_depart} 가 하나를
     * 거부한다. 그 거부를 옮기지 않으면 {@code DataIntegrityViolationException} 이 그대로 올라가
     * 배치가 죽는다({@code BusCommandService.enforcingUniqueBusNo} 와 같은 형태).
     *
     * <p><b>예외로 알리고 삼키지 않는 것</b>이 두 호출부를 함께 만족시키는 유일한 형태다. 제약 위반이
     * 난 트랜잭션은 되돌려야 하므로(Hibernate 세션이 더 이상 쓸 수 없는 상태다) 여기서 잡아 조용히
     * 넘어갈 수 없다 — 넘어가면 커밋 시점에 {@code UnexpectedRollbackException} 이 된다. 배치는 이
     * 예외를 <b>트랜잭션 밖에서</b> 받아 건너뛴다({@code RunGenerationService}).
     */
    public Run create(RunDraft draft) {
        OffsetDateTime departAt = departureOf(draft);
        if (runRepository.existsByAcademyIdAndBusIdAndServiceDateAndDirectionAndDepartTime(
                draft.academyId(), draft.busId(), draft.serviceDate(), draft.direction(), departAt)) {
            throw new BusinessException(ErrorCode.DUPLICATE_RUN);
        }
        Run run = Run.forSchedule(draft.academyId(), draft.busId(), draft.scheduleId(), draft.serviceDate(),
                draft.direction(), departAt, RunConfirmationPolicy.confirmAtOf(departAt), draft.originName(),
                draft.destinationName(), draft.estDurationMin());
        try {
            return runRepository.saveAndFlush(run);
        } catch (DataIntegrityViolationException e) {
            if (isSlotViolation(e)) {
                throw new BusinessException(ErrorCode.DUPLICATE_RUN);
            }
            throw e;
        }
    }

    /**
     * 특정일 회차를 임시로 취소한다(SCH-03, §5.10) — 행을 지우지 않고 {@code canceled_at} 을 채운다.
     *
     * <p>대상이 다른 학원이면 {@code 404 RUN_NOT_FOUND} 다.
     */
    public void cancel(AuthUser requester, Long runId) {
        runRepository.findByIdAndAcademyId(runId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND))
                .cancel(OffsetDateTime.now(clock));
    }

    /**
     * 날짜 없는 출발 시각({@code schedule.depart_time})을 그날의 {@code timestamptz} 로 확정한다.
     *
     * <p>시간대를 <b>주입된 {@code Clock} 에서</b> 가져온다(횡단 규칙 1 · Ruling 165 ①) —
     * {@code ZoneId} 를 코드에 다시 적으면 테스트가 시계를 갈아끼워도 이 계산만 시스템 시간대를 따라
     * 남아, 확정 시각 단언이 실행 환경에 따라 갈린다.
     */
    private OffsetDateTime departureOf(RunDraft draft) {
        return draft.serviceDate().atTime(draft.departTime()).atZone(clock.getZone()).toOffsetDateTime();
    }

    /** 원인 체인에서 {@link ConstraintViolationException} 을 찾아 거부한 주체가 유일성 제약인지만 본다. */
    private boolean isSlotViolation(DataIntegrityViolationException e) {
        return e.getCause() instanceof ConstraintViolationException cve
                && RUN_SLOT_UNIQUE_CONSTRAINT.equals(cve.getConstraintName());
    }
}
