package src.backend.global.dev;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Set;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.data.redis.core.StringRedisTemplate;

import src.backend.request.preview.spec.ApprovalPreviewCache;

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
    private final StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
    private final ApprovalPreviewCache previewCache = mock(ApprovalPreviewCache.class);

    private final DevResetService service = new DevResetService(flyway, migrationStrategy, stringRedisTemplate,
            previewCache);

    @Test
    void 리셋하면_미리보기_캐시도_비운다() {
        given(stringRedisTemplate.keys("run:*:position")).willReturn(Set.of());

        service.reset();

        verify(previewCache).clear();
    }
}
