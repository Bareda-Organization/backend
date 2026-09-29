package src.backend.schedule.scheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 기동 직후 오늘·내일 회차 생성을 한 번 돈다(BR-017, API_SPEC §5.10 "재기동·수동 재실행이 정상 동작") — 하루 1회 cron 이라
 * 00:05 에 서버가 내려가 있었으면(배포·재시작) 그날·내일 회차가 0건이고 다음 실행은 내일 00:05 이다. 이미 있는 회차는 UNIQUE
 * 로 건너뛰므로 멱등이다. 시험은 {@code app.run.generation.on-startup=false} 로 끈다(배경 생성이 시험 데이터를 바꾼다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.run.generation.on-startup", havingValue = "true", matchIfMissing = true)
public class DailyRunStartupCatchUp {

    private final DailyRunGenerator dailyRunGenerator;

    /** 실패해도 기동을 막지 않는다 — 로그를 남기고 00:05 cron 이 다시 돈다. */
    @EventListener(ApplicationReadyEvent.class)
    public void generateOnStartup() {
        try {
            dailyRunGenerator.generateTodayAndTomorrow();
        } catch (RuntimeException e) {
            log.warn("기동 직후 회차 보충 생성 실패 — 00:05 일일 생성이 다시 돈다", e);
        }
    }
}
