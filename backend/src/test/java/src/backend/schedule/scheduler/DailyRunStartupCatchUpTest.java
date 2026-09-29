package src.backend.schedule.scheduler;

import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 재기동 보충(BR-017, API_SPEC §5.10 "재기동·수동 재실행이 정상 동작") — 00:05 에 서버가 내려가 있었으면 그날 회차가
 * 없고 다음 실행은 내일이다. 기동이 끝나면 오늘·내일 회차 생성을 한 번 돈다(UNIQUE 로 멱등).
 */
@SpringBootTest(properties = "app.run.generation.on-startup=true")
class DailyRunStartupCatchUpTest {

    @MockitoBean
    private DailyRunGenerator dailyRunGenerator;

    @Test
    void 기동이_끝나면_오늘과_내일_회차_생성을_돈다() {
        verify(dailyRunGenerator, atLeastOnce()).generateTodayAndTomorrow();
    }
}
