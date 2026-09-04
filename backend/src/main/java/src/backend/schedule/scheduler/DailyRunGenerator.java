package src.backend.schedule.scheduler;

import java.time.Clock;
import java.time.LocalDate;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.schedule.command.RunGenerationService;

/**
 * 일일 회차 생성 배치(SCH-02, API_SPEC §5.10) — 하루 한 번 그날의 회차를 만든다.
 *
 * <p>전용 엔드포인트를 두지 않는다(§5.10) — 회차 생성은 요청이 아니라 <b>날짜가 바뀌는 것</b>이
 * 일으키는 일이고, 관계자가 손으로 부르는 경로를 열면 그 경로가 곧 중복 실행의 통로가 된다.
 *
 * <p>이 클래스가 하는 일은 <b>"오늘이 며칠인가" 를 정하는 것뿐</b>이다. 무엇을 만들지는
 * {@link RunGenerationService} 가 정한다 — 실행 시각과 대상 날짜를 가르면 지난 날짜를 다시 만드는
 * 운영 조작이 그 서비스를 그대로 부르는 것으로 가능해진다(ARCHITECTURE §9.2 두 시계).
 */
@Component
@RequiredArgsConstructor
public class DailyRunGenerator {

    private final RunGenerationService runGenerationService;

    private final Clock clock;

    /**
     * 오늘의 회차를 만든다.
     *
     * <p>실행 시각을 설정으로 받는 이유는 <b>테스트에서 배경 실행을 끄기 위함</b>이다 — 배경 배치가
     * 테스트가 만든 스케줄을 먼저 집으면 생성 건수 단언이 실행 시각에 따라 갈린다
     * ({@code build.gradle} 이 {@code -}(비활성)를 넣는다). 빈을 없애지 않고 표현식만 바꾸는 이유는,
     * 없애면 {@code @Scheduled} 배선 자체가 어느 테스트에도 걸리지 않아 애너테이션을 지워도 초록이
     * 되기 때문이다({@code NotificationOutboxWorker} 와 같은 형태).
     *
     * <p><b>어느 날의 회차인가는 주입된 {@code Clock} 이 정한다</b>(횡단 규칙 1). {@code cron} 의
     * {@code zone} 은 애너테이션이라 상수만 받아 {@code Clock} 에서 가져올 수단이 부재하고, 그래서
     * 서비스 기준 시간대({@code Asia/Seoul}, ERD §2 · {@code ClockConfig} 와 같은 값)를 적는다 —
     * 이 값이 정하는 것은 <b>언제 도는가</b>뿐이고, 시계를 갈아끼운 테스트는 {@code generate} 를 직접
     * 불러 대상 날짜를 고정한다.
     *
     * <p>{@code @SchedulerLock}(TECH_DECISIONS §3.2) — 하루 1회뿐이라 경합 확률은 낮지만, 겹치면
     * {@code DUPLICATE_RUN} 예외가 전 학원 건수만큼 반복돼 로그가 무의미하게 시끄러워진다.
     * {@code lockAtMostFor} 를 30분으로 넉넉히 잡은 이유는 실행 자체가 하루 1번뿐이라 락 비용이
     * 아니라 <b>죽은 인스턴스가 락을 오래 들고 있어도 다음 실행은 내일이라 당장 급하지 않다</b>는
     * 점을 반영한 것이다 — 그래도 무기한으로 두지 않는 이유는 수동 재실행 운영 조작(API_SPEC §5.10)이
     * 그 안에서는 락에 막히지 않게 하려는 것이다.
     */
    @Scheduled(cron = "${app.run.generation.cron:0 5 0 * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "daily-run-generator", lockAtMostFor = "PT30M")
    public void generateToday() {
        runGenerationService.generate(LocalDate.now(clock));
    }
}
