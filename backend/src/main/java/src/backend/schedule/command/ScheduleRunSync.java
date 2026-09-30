package src.backend.schedule.command;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.common.enums.Weekday;
import src.backend.run.command.RunCommandService;
import src.backend.run.entity.Run;
import src.backend.global.security.AuthUser;
import src.backend.run.command.RunCancellation;
import src.backend.run.entity.RunCancelSource;
import src.backend.run.entity.RunDraft;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.RunRepository;
import src.backend.schedule.entity.Schedule;

/**
 * 스케줄 등록·수정·삭제를 <b>미리 만든 내일 회차</b>에 반영한다(Ruling 366 ②, API_SPEC §5.10) — 회차를 하루
 * 앞서 만들면 "오늘 고친 스케줄이 내일에 안 먹는" 결함이 생기므로 스케줄 변경과 같은 트랜잭션에서 맞춘다.
 *
 * <p>대상은 그 스케줄이 만든 {@code service_date > 오늘} · {@code idle} · 미취소 회차뿐이다. <b>오늘 회차는
 * 건드리지 않는다</b>(확정 배치가 걸려 있을 수 있다). 이미 확정·시작·취소된 회차도 그대로다.
 *
 * <p>스케줄이 더는 그 회차를 뒷받침하지 않으면(비활성 · 요일 또는 방향이 다름 · 삭제) 행을 지우지 않고 취소 표시한다
 * — 이미 붙은 탑승 의사·변경 신청 행이 있을 수 있어서다. 뒷받침하면 속성을 옮긴다.
 *
 * <p>이 취소에는 출처 {@link RunCancelSource#SCHEDULE} 를 남긴다(Ruling 367 ②). 스케줄이 다시 뒷받침하면(재활성 ·
 * 요일/방향 복귀) 그 출처의 내일 이후 {@code idle} 회차만 취소를 풀고 계획을 다시 옮긴다 — 관계자가 직접 취소한
 * 회차({@link RunCancelSource#STAFF})는 절대 되살리지 않는다.
 */
@Component
@RequiredArgsConstructor
class ScheduleRunSync {

    private final RunRepository runRepository;

    private final RunCommandService runCommandService;

    private final RunCancellation runCancellation;

    private final Clock clock;

    /**
     * 등록·수정 뒤 — 미래 회차를 맞추고, 활성이고 요일이 내일이면 내일 회차를 (없으면) 만든다. 이미 확정·시작돼 옮기지
     * 못한 살아 있는 회차가 내일에 있으면 새로 만들지 않는다(같은 스케줄의 회차가 둘이 된다).
     */
    void reflect(AuthUser requester, Schedule schedule) {
        LocalDate today = LocalDate.now(clock);
        for (Run run : upcomingIdleRuns(schedule, today)) {
            if (backs(schedule, run)) {
                runCommandService.moveToPlan(run, draftOf(schedule, run.getServiceDate()));
            } else {
                runCancellation.cancel(requester, run, RunCancelSource.SCHEDULE);
            }
        }
        reinstateBacked(schedule, today);
        LocalDate tomorrow = today.plusDays(1);
        if (schedule.isActive() && schedule.getWeekday() == Weekday.of(tomorrow)
                && !runRepository.existsByAcademyIdAndScheduleIdAndServiceDateAndDirectionAndCanceledAtIsNull(
                        schedule.getAcademyId(), schedule.getId(), tomorrow, schedule.getDirection())) {
            runCommandService.createIfAbsent(draftOf(schedule, tomorrow));
        }
    }

    /** 삭제 전 — 미래 회차를 전부 취소 표시한다(삭제되면 {@code run.schedule_id} 가 비워져 더는 찾을 수 없다). */
    void cancelUpcoming(AuthUser requester, Schedule schedule) {
        upcomingIdleRuns(schedule, LocalDate.now(clock))
                .forEach(run -> runCancellation.cancel(requester, run, RunCancelSource.SCHEDULE));
    }

    /**
     * 스케줄이 취소했던 회차 중 <b>지금 다시 뒷받침되는 것</b>의 취소를 풀고 계획을 옮긴다. 같은 스케줄·날짜·방향의
     * 살아 있는 회차가 이미 있으면 되살리지 않는다(같은 스케줄의 회차가 둘이 된다).
     */
    private void reinstateBacked(Schedule schedule, LocalDate today) {
        for (Run run : runRepository.findAllByAcademyIdAndScheduleIdAndServiceDateAfterAndStatusAndCancelSource(
                schedule.getAcademyId(), schedule.getId(), today, RunStatus.IDLE, RunCancelSource.SCHEDULE)) {
            if (backs(schedule, run) && !hasLiveRun(schedule, run)) {
                runCommandService.reinstateToPlan(run, draftOf(schedule, run.getServiceDate()));
            }
        }
    }

    private boolean hasLiveRun(Schedule schedule, Run run) {
        return runRepository.existsByAcademyIdAndScheduleIdAndServiceDateAndDirectionAndCanceledAtIsNull(
                schedule.getAcademyId(), schedule.getId(), run.getServiceDate(), run.getDirection());
    }

    private List<Run> upcomingIdleRuns(Schedule schedule, LocalDate today) {
        return runRepository.findAllByAcademyIdAndScheduleIdAndServiceDateAfterAndStatusAndCanceledAtIsNull(
                schedule.getAcademyId(), schedule.getId(), today, RunStatus.IDLE);
    }

    /** 스케줄이 그 회차를 계속 뒷받침하는가 — 활성이고 회차의 요일·방향이 스케줄과 같다. */
    private boolean backs(Schedule schedule, Run run) {
        return schedule.isActive() && schedule.getDirection() == run.getDirection()
                && schedule.getWeekday() == Weekday.of(run.getServiceDate());
    }

    private RunDraft draftOf(Schedule schedule, LocalDate serviceDate) {
        return new RunDraft(schedule.getAcademyId(), schedule.getBusId(), schedule.getId(), serviceDate,
                schedule.getDirection(), schedule.getDepartTime(), schedule.getOriginName(),
                schedule.getDestinationName(), schedule.getEstDurationMin());
    }
}
