package src.backend.schedule.command;

import java.util.function.Supplier;

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
import src.backend.schedule.dto.ScheduleRegisterRequest;
import src.backend.schedule.dto.ScheduleResponse;
import src.backend.schedule.dto.ScheduleUpdateRequest;
import src.backend.schedule.entity.Schedule;
import src.backend.schedule.entity.SchedulePlan;
import src.backend.schedule.repository.ScheduleRepository;

/** 운행 스케줄 등록·수정·삭제(SCH-01, API_SPEC §5.10). */
@Service
@RequiredArgsConstructor
@Transactional
public class ScheduleCommandService {

    /**
     * 유일성 조합을 강제하는 제약 이름({@code V1__init_schema.sql}).
     *
     * <p>이름으로 가리는 이유는 {@code schedule} 에 CHECK 가 둘 더 있기 때문이다
     * ({@code ck_schedule_weekday}·{@code ck_schedule_direction}). 제약을 가리지 않고
     * {@code DataIntegrityViolationException} 을 통째로 409 로 옮기면 값 도메인 위반까지 "이미 등록된
     * 스케줄입니다" 로 답해 원인을 감춘다({@code BusCommandService} 와 같은 형태).
     */
    private static final String SCHEDULE_SLOT_UNIQUE_CONSTRAINT = "uk_schedule_bus_weekday_direction_depart";

    private final ScheduleRepository scheduleRepository;

    private final BusRepository busRepository;

    /**
     * 스케줄을 등록한다(§5.10) — 소속 학원은 토큰에서만 온다(§1.5).
     *
     * <p>차량을 <b>먼저</b> 학원으로 좁혀 확인한다 — 그러지 않으면 남의 학원 차량으로 스케줄을 세울 수
     * 있고, 그 스케줄이 만드는 회차는 어느 학원 것인지가 두 테이블에서 갈린다.
     */
    public ScheduleResponse register(AuthUser requester, ScheduleRegisterRequest request) {
        Bus bus = findOwnBus(requester, request.busId());
        SchedulePlan plan = planOf(request);
        Schedule schedule = Schedule.register(requester.academyId(), plan);
        return enforcingUniqueSlot(requester.academyId(), plan,
                () -> ScheduleResponse.of(scheduleRepository.save(schedule), bus.getBusNo()));
    }

    /**
     * 스케줄을 고친다(§5.10) — 대상이 다른 학원이면 {@code 404 SCHEDULE_NOT_FOUND} 다.
     *
     * <p>유일성 조합을 <b>실제로 옮기는</b> 수정에만 중복 검사를 건다({@link Schedule#movesSlot}) —
     * 같은 값을 그대로 다시 보내는 요청이 자기 자신을 중복으로 세는 것을 막는다.
     */
    public ScheduleResponse update(AuthUser requester, Long scheduleId, ScheduleUpdateRequest request) {
        Schedule schedule = findOwnSchedule(requester, scheduleId);
        SchedulePlan plan = planOf(request);
        Bus bus = findOwnBus(requester, plan.busId() == null ? schedule.getBusId() : plan.busId());
        boolean movesSlot = schedule.movesSlot(plan);
        Supplier<ScheduleResponse> apply = () -> {
            schedule.update(plan);
            return ScheduleResponse.of(schedule, bus.getBusNo());
        };
        return movesSlot ? enforcingUniqueSlot(requester.academyId(), merged(schedule, plan), apply) : apply.get();
    }

    /**
     * 스케줄을 삭제한다(§5.10) — soft delete 가 아니라 <b>행을 지운다</b>({@code schedule} 에
     * {@code deleted_at} 컬럼이 부재하다).
     *
     * <p>이미 만들어진 회차는 남는다 — {@code fk_run_schedule} 이 {@code ON DELETE SET NULL} 이라
     * {@code run.schedule_id} 만 비워진다. CASCADE 였다면 스케줄 정리 한 번이 과거 운행 기록을 함께
     * 지운다.
     */
    public void delete(AuthUser requester, Long scheduleId) {
        scheduleRepository.delete(findOwnSchedule(requester, scheduleId));
    }

    private Schedule findOwnSchedule(AuthUser requester, Long scheduleId) {
        return scheduleRepository.findByIdAndAcademyId(scheduleId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND));
    }

    private Bus findOwnBus(AuthUser requester, Long busId) {
        return busRepository.findByIdAndAcademyId(busId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.BUS_NOT_FOUND));
    }

    private SchedulePlan planOf(ScheduleRegisterRequest request) {
        return new SchedulePlan(request.busId(), ApiValues.weekday(request.weekday()),
                ApiValues.direction(request.direction()), ApiValues.time(request.departTime()),
                request.originName(), request.destinationName(), request.estDurationMin(), request.active());
    }

    private SchedulePlan planOf(ScheduleUpdateRequest request) {
        return new SchedulePlan(request.busId(), ApiValues.weekday(request.weekday()),
                ApiValues.direction(request.direction()), ApiValues.time(request.departTime()),
                request.originName(), request.destinationName(), request.estDurationMin(), request.active());
    }

    /** 선검사가 볼 조합 — 요청이 준 값이 우선이고 주지 않은 항목은 지금 스케줄의 값이다. */
    private SchedulePlan merged(Schedule schedule, SchedulePlan plan) {
        return new SchedulePlan(
                plan.busId() == null ? schedule.getBusId() : plan.busId(),
                plan.weekday() == null ? schedule.getWeekday() : plan.weekday(),
                plan.direction() == null ? schedule.getDirection() : plan.direction(),
                plan.departTime() == null ? schedule.getDepartTime() : plan.departTime(),
                null, null, null, null);
    }

    /**
     * 유일성 조합을 강제하며 작업을 실행한다 — 위반은 선검사에서든 DB 거부에서든 같은
     * {@code 409 DUPLICATE_SCHEDULE} 다(Ruling 153).
     *
     * <p><b>선검사만으로는 부족하다.</b> 동시 요청 2건은 서로의 미커밋 INSERT 를 보지 못한 채 둘 다
     * 선검사를 지나고, 그 뒤 {@code uk_schedule_bus_weekday_direction_depart} 가 하나를 거부한다. 그
     * 거부를 옮기지 않으면 {@code 500} 이 나가 "서버가 고장났다" 와 "이미 있는 스케줄이다" 가
     * 구별되지 않는다.
     *
     * <p>{@code action} 실행 후 즉시 flush 하는 것이 수정 경로 때문에 필요하다 — 조합 변경은 <b>변경
     * 감지</b>로만 DB 에 닿아서, flush 하지 않으면 {@code UPDATE} 가 커밋 시점까지 미뤄지고 제약
     * 위반이 이 {@code try} 밖에서 터진다({@code BusCommandService} 가 같은 자리를 이미 푼다).
     */
    private <T> T enforcingUniqueSlot(Long academyId, SchedulePlan slot, Supplier<T> action) {
        if (scheduleRepository.existsByAcademyIdAndBusIdAndWeekdayAndDirectionAndDepartTime(
                academyId, slot.busId(), slot.weekday(), slot.direction(), slot.departTime())) {
            throw new BusinessException(ErrorCode.DUPLICATE_SCHEDULE);
        }
        try {
            T result = action.get();
            scheduleRepository.flush();
            return result;
        } catch (DataIntegrityViolationException e) {
            if (isSlotViolation(e)) {
                throw new BusinessException(ErrorCode.DUPLICATE_SCHEDULE);
            }
            throw e;
        }
    }

    /** 원인 체인에서 {@link ConstraintViolationException} 을 찾아 거부한 주체가 유일성 제약인지만 본다. */
    private boolean isSlotViolation(DataIntegrityViolationException e) {
        return e.getCause() instanceof ConstraintViolationException cve
                && SCHEDULE_SLOT_UNIQUE_CONSTRAINT.equals(cve.getConstraintName());
    }
}
