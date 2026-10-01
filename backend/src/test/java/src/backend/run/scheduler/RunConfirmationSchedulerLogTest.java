package src.backend.run.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.observability.metrics.RunConfirmationMetrics;
import src.backend.run.command.RunConfirmationService;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;

/**
 * R46-KFIXBE K-2(Ruling 703) — 확정 실패 로그. 노선·학원 좌표를 사람이 고칠 때까지 같은 결과인 설정 오류({@link BusinessException})는 스택 없이
 * 한 줄이고, 예기치 못한 예외만 스택을 남기되 분당 한 번이다. 시험 전에는 실패마다 스택 약 20줄이 30초마다 찍혔다(시드 회차 4건이 13분에 스택 120줄).
 */
class RunConfirmationSchedulerLogTest {

    private final RunRepository runRepository = mock(RunRepository.class);

    private final RunConfirmationService confirmationService = mock(RunConfirmationService.class);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private ListAppender<ILoggingEvent> appender;

    private Logger logger;

    @BeforeEach
    void collectLogs() {
        logger = (Logger) LoggerFactory.getLogger(RunConfirmationScheduler.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Run run = mock(Run.class);
        when(run.getId()).thenReturn(1L);
        when(runRepository.findDueForConfirmation(any(), any(), any(), any(Pageable.class))).thenReturn(List.of(run));
    }

    @AfterEach
    void stopCollecting() {
        logger.detachAppender(appender);
        executor.shutdownNow();
    }

    @Test
    void 설정_오류_실패는_스택_없이_한_줄이고_예기치_못한_예외만_스택을_남긴다() {
        RunConfirmationScheduler scheduler = new RunConfirmationScheduler(runRepository, confirmationService,
                executor, Clock.fixed(Instant.parse("2030-04-01T00:00:00Z"), ZoneOffset.UTC),
                mock(RunConfirmationMetrics.class));
        doThrow(new BusinessException(ErrorCode.ROUTE_NOT_CONFIGURED_FOR_RUN)).when(confirmationService)
                .confirmOne(anyLong());

        for (int i = 0; i < 5; i++) {
            scheduler.confirmDueRuns();
        }

        assertThat(appender.list).as("틱마다 한 줄").hasSize(5);
        assertThat(appender.list).allSatisfy(event -> assertThat(event.getThrowableProxy())
                .as("설정 오류는 스택이 필요 없다").isNull());

        appender.list.clear();
        doThrow(new IllegalStateException("예기치 못한 오류")).when(confirmationService).confirmOne(anyLong());

        for (int i = 0; i < 5; i++) {
            scheduler.confirmDueRuns();
        }

        assertThat(appender.list).as("분당 한 번만 — 나머지는 센다").hasSize(1);
        assertThat(appender.list.getFirst().getThrowableProxy()).as("예기치 못한 예외는 스택을 남긴다").isNotNull();
    }
}
