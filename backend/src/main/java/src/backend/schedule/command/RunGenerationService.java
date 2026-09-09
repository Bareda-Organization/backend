package src.backend.schedule.command;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import src.backend.global.common.enums.Weekday;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.run.command.RunCommandService;
import src.backend.run.entity.RunDraft;
import src.backend.schedule.entity.Schedule;
import src.backend.schedule.repository.ScheduleRepository;

/**
 * 일일 회차 생성(SCH-02, API_SPEC §5.10) — 그날 요일의 <b>활성</b> 스케줄을 그날의 회차로 옮긴다.
 *
 * <p><b>중복 실행은 오류가 아니라 무시다.</b> 재기동·수동 재실행이 정상 동작이므로 이미 있는 회차는
 * 조용히 건너뛰고 생성 건수만 센다 — {@code 409} 를 던지면 배치가 두 번째 실행에서 통째로 실패하고,
 * 그 실패는 "정말 만들 수 없었다" 와 구별되지 않는다.
 *
 * <p><b>이 클래스에 {@code @Transactional} 이 부재한 것이 요점이다.</b> 회차 하나가 곧 트랜잭션
 * 하나여야 멱등이 성립한다 — 전부를 한 트랜잭션에 묶으면 제약 위반 하나가 그 실행에서 만든 나머지
 * 회차까지 되돌리고, 예외로 더럽혀진 영속성 문맥은 이어서 쓸 수도 없다. 그래서 건너뛸지 말지의 판정을
 * {@link RunCommandService#create} <b>바깥</b>에서 한다.
 */
@Service
@RequiredArgsConstructor
public class RunGenerationService {

    private final ScheduleRepository scheduleRepository;

    private final RunCommandService runCommandService;

    /**
     * 그 날짜의 회차를 만든다 — <b>새로 만든</b> 건수를 돌려준다(이미 있던 것은 세지 않는다).
     *
     * <p>날짜를 파라미터로 받는다 — "실행 시각" 과 "어느 날의 회차인가" 는 다른 값이고
     * (ARCHITECTURE §9.2 두 시계), 하나로 묶으면 지난 날짜를 다시 만들거나 내일 것을 미리 만드는
     * 운영 조작이 불가능해진다. 배치는 {@code DailyRunGenerator} 가 오늘 날짜로 부른다.
     */
    public int generate(LocalDate serviceDate) {
        List<Schedule> schedules = scheduleRepository.findAllByWeekdayAndActiveIsTrue(weekdayOf(serviceDate));
        int created = 0;
        for (Schedule schedule : schedules) {
            if (creates(schedule, serviceDate)) {
                created++;
            }
        }
        return created;
    }

    /**
     * 회차 1건을 만든다 — 이미 있으면 건너뛰고 {@code false} 를 돌려준다.
     *
     * <p>{@code DUPLICATE_RUN} <b>만</b> 삼킨다. 다른 오류까지 함께 삼키면 차량이 사라진 스케줄·정책
     * 위반 같은 실제 결함이 "0 건 생성" 으로 조용히 지나가고, 아무도 회차가 왜 없는지 답할 수 없다.
     */
    private boolean creates(Schedule schedule, LocalDate serviceDate) {
        try {
            runCommandService.create(draftOf(schedule, serviceDate));
            return true;
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.DUPLICATE_RUN) {
                return false;
            }
            throw e;
        }
    }

    /** 스케줄이 담은 계획을 그날의 회차 입력으로 옮긴다 — 출발지·도착지·소요시간을 그대로 물려준다. */
    private RunDraft draftOf(Schedule schedule, LocalDate serviceDate) {
        return new RunDraft(schedule.getAcademyId(), schedule.getBusId(), schedule.getId(), serviceDate,
                schedule.getDirection(), schedule.getDepartTime(), schedule.getOriginName(),
                schedule.getDestinationName(), schedule.getEstDurationMin());
    }

    /**
     * 그 날짜의 요일 — {@code schedule.weekday} 의 값 공간으로 옮긴다.
     *
     * <p>{@code LocalDate} 자체가 요일을 들고 있으므로 시계를 보지 않는다. 시계가 필요한 것은 "오늘이
     * 며칠인가" 뿐이고 그 판정은 이 메서드 밖에 있다 — 섞으면 지난 날짜를 다시 만들 때 오늘 요일로
     * 스케줄을 고르게 된다.
     */
    private Weekday weekdayOf(LocalDate serviceDate) {
        return Weekday.valueOf(serviceDate.getDayOfWeek().name().substring(0, 3).toUpperCase(Locale.ROOT));
    }
}
