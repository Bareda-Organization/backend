package src.backend.global.dev;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
}
