package src.backend.schedule.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import src.backend.global.common.enums.Weekday;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.run.command.RunCommandService;
import src.backend.run.entity.RunDraft;
import src.backend.schedule.entity.Schedule;
import src.backend.schedule.repository.ScheduleRepository;

/**
 * 일일 회차 생성 배치(SCH-02)가 <b>어떤 실패를 삼키고 어떤 실패를 드러내는가</b>를 고정한다.
 *
 * <p>{@code DUPLICATE_RUN} 만 건너뛰기의 근거다 — 배치의 중복 실행은 재기동·수동 재실행이라는 정상
 * 동작이라 오류가 아니다. 그 밖의 실패까지 함께 삼키면 <b>"0 건 생성" 이 정상과 고장을 같은 값으로
 * 답한다</b>: 회차가 왜 없는지 되물을 근거가 응답에도 로그에도 남지 않는다.
 *
 * <p>스프링을 띄우지 않고 협력자를 가짜로 세우는 이유는, 지금의 {@link RunCommandService#create} 가
 * {@code DUPLICATE_RUN} 외의 {@link BusinessException} 을 낼 경로를 갖고 있지 않기 때문이다. 실제
 * 경로로는 이 분기를 밟을 수단이 부재하고, 그대로 두면 <b>다음 Phase 가 검증을 하나 더 붙이는 순간</b>
 * 그 실패가 조용히 건너뛰기로 바뀐다 — 이 테스트는 그때 깨지라고 있다.
 */
class RunGenerationExceptionScopeTest {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2031, 6, 11);

    private final ScheduleRepository scheduleRepository = mock(ScheduleRepository.class);

    private final RunCommandService runCommandService = mock(RunCommandService.class);

    private final RunGenerationService runGenerationService =
            new RunGenerationService(scheduleRepository, runCommandService);

    /** 중복은 건너뛴다 — 그 스케줄을 세지 않을 뿐 배치는 계속 돈다. */
    @Test
    void 중복_회차는_건너뛰고_생성_건수에서_빠진다() {
        스케줄_하나가_있다();
        when(runCommandService.create(any(RunDraft.class)))
                .thenThrow(new BusinessException(ErrorCode.DUPLICATE_RUN));

        assertThat(runGenerationService.generate(SERVICE_DATE)).isZero();
    }

    /**
     * 중복이 <b>아닌</b> 실패는 드러낸다 — 배치가 그 자리에서 멈추고 예외가 그대로 올라간다.
     *
     * <p>이 단언이 없으면 {@code catch} 가 오류 코드를 보지 않는 구현이 통과하고, 그 구현에서는
     * 차량이 사라진 스케줄·정책 위반이 전부 "만들 것이 없었다" 로 보고된다.
     */
    @Test
    void 중복이_아닌_실패는_삼키지_않고_드러낸다() {
        스케줄_하나가_있다();
        when(runCommandService.create(any(RunDraft.class)))
                .thenThrow(new BusinessException(ErrorCode.BUS_NOT_FOUND));

        assertThatThrownBy(() -> runGenerationService.generate(SERVICE_DATE))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.BUS_NOT_FOUND);
    }

    /** 그날 요일의 활성 스케줄이 한 건 있는 상태 — 회차 생성이 한 번은 시도된다. */
    private void 스케줄_하나가_있다() {
        when(scheduleRepository.findAllByWeekdayAndActiveIsTrue(any(Weekday.class)))
                .thenReturn(List.of(mock(Schedule.class)));
    }
}
