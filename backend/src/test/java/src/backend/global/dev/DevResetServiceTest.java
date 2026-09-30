package src.backend.global.dev;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;


import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;

import src.backend.location.infrastructure.RunPositionStore;
import src.backend.request.preview.spec.ApprovalPreviewCache;
import src.backend.schedule.command.RunGenerationService;

/**
 * {@link DevResetService#reset()} 이 위치 캐시뿐 아니라 승인 미리보기 캐시도 지우는지 고정한다.
 *
 * <p>이 서비스는 {@code app.dev-tools.reset.enabled=false} 로 테스트 컨텍스트에 등록되지 않는다
 * ({@link DevResetEndpointGuardTest}). 그래서 Spring 컨텍스트를 띄우지 않고 의존성을 직접 mock 해
 * 검증한다 — 지우지 않으면 리셋 직후 첫 조회가 "이전 캐시를 교체했다" 는 이유만으로 {@code stale=true}
 * 로 잘못 분류된다({@code ApprovalPreviewResolver#resolvePreview}).
 */
class DevResetServiceTest {

    private final Flyway flyway = mock(Flyway.class);
    private final FlywayMigrationStrategy migrationStrategy = mock(FlywayMigrationStrategy.class);
    private final RunPositionStore runPositionStore = mock(RunPositionStore.class);
    private final ApprovalPreviewCache previewCache = mock(ApprovalPreviewCache.class);

    private final RunGenerationService runGenerationService = mock(RunGenerationService.class);

    /** 고정 시계 — 2026-08-26(수) 09:00 KST 라 내일은 2026-08-27 이다. */
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneId.of("Asia/Seoul"));

    private final DevResetService service = new DevResetService(flyway, migrationStrategy, runPositionStore,
            previewCache, runGenerationService, clock);

    @Test
    void 리셋하면_미리보기_캐시도_비운다() {
        service.reset();

        verify(previewCache).clear();
    }

    /**
     * 초기화 뒤 <b>내일</b> 회차만 만든다(Ruling 367 ④) — 스테이징 초기화 버튼 뒤에도 학부모 "내일" 변경을 시험할 수
     * 있어야 한다. 오늘 회차는 만들지 않는다: 웹·Flutter 실서버 계약 시험이 초기화 직후의 시드 상태(오늘 회차 없음)에
     * 기대므로, 오늘까지 만들면 그 시험이 값으로 깨진다. 스키마를 다시 깐 <b>뒤에</b> 만들어야 지워지지 않는다.
     */
    @Test
    void 리셋하면_시드를_다시_깐_뒤_내일_회차만_만든다() {
        service.reset();

        InOrder order = inOrder(migrationStrategy, runGenerationService);
        order.verify(migrationStrategy).migrate(flyway);
        order.verify(runGenerationService).generate(LocalDate.of(2026, 8, 27));
        verify(runGenerationService, never()).generate(LocalDate.of(2026, 8, 26));
    }

    /**
     * 초기화가 도는 동안 다른 트랜잭션(배치·요청)이 같은 테이블을 잡고 있으면 Postgres 가 교착을 감지해 한쪽을
     * 중단시키고, 그 희생자가 {@code DROP TABLE academy CASCADE} 였을 때 첫 호출이 {@code 500} 이었다(R38-B).
     * {@code clean()} 은 몇 번을 다시 해도 같은 결과라 교착이면 처음부터 다시 한다.
     */
    @Test
    void 교착으로_중단되면_다시_시도해_성공한다() {
        doThrow(new RuntimeException("Unable to drop academy",
                new SQLException("deadlock detected", "40P01")))
                .doNothing().when(migrationStrategy).migrate(flyway);

        service.reset();

        verify(migrationStrategy, times(2)).migrate(flyway);
        verify(runGenerationService).generate(LocalDate.of(2026, 8, 27));
    }

    /** 교착이 아닌 실패(접속 거부·SQL 오류)까지 되풀이하면 원인이 가려진다 — 첫 실패를 그대로 올린다. */
    @Test
    void 교착이_아닌_실패는_다시_시도하지_않는다() {
        doThrow(new IllegalStateException("localhost 가 아니다")).when(migrationStrategy).migrate(flyway);

        assertThatThrownBy(service::reset).isInstanceOf(IllegalStateException.class);
        verify(migrationStrategy, times(1)).migrate(flyway);
    }

    /** 교착이 끝없이 이어지면 무한히 붙들지 않고 포기해 원인을 그대로 올린다. */
    @Test
    void 교착이_계속되면_횟수를_채우고_포기한다() {
        doThrow(new RuntimeException("Unable to drop academy",
                new SQLException("deadlock detected", "40P01")))
                .when(migrationStrategy).migrate(flyway);

        assertThatThrownBy(service::reset).hasRootCauseInstanceOf(SQLException.class);
        verify(migrationStrategy, times(DevResetService.MAX_MIGRATE_ATTEMPTS)).migrate(flyway);
        verify(previewCache, never()).clear();
    }
}
