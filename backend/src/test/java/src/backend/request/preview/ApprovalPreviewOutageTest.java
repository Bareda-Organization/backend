package src.backend.request.preview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.redis.core.StringRedisTemplate;

import tools.jackson.databind.json.JsonMapper;

import src.backend.request.preview.ApprovalPreviewResolver.OriginDestination;
import src.backend.request.preview.impl.RedisApprovalPreviewCache;
import src.backend.global.location.GeoPoint;
import src.backend.routing.engine.spec.OrderedStop;
import src.backend.routing.entity.RouteVersionSource;
import src.backend.routing.pipeline.ComputationSnapshot;
import src.backend.routing.pipeline.DailyRoster;
import src.backend.routing.pipeline.RouteComputation;
import src.backend.routing.pipeline.RouteComputationPipeline;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.routing.repository.WaypointRepository;
import src.backend.run.entity.Run;
import src.backend.student.repository.StopRepository;
import testsupport.redis.Outage;
import testsupport.redis.OutageRedis;
import testsupport.redis.OutageRedisContext;

/**
 * Redis 가 멈추거나 답하지 않는 동안 승인 상세 조회 — 같은 입력의 반복 조회가 지도 API 를 매번 부르지 않는다(Ruling 919 B ⑤). 캐시를 못 읽으면 "없음" 으로 보고 다시
 * 계산하는데, 그대로 두면 화면을 열 때마다 노선 계산(지도 API 호출)이 늘어 일일 한도를 쓴다. 그래서 Redis 를 못 쓰는 동안은 이 인스턴스 안의 짧은 기억이 같은 입력의
 * 미리보기를 대신 들고 있고, 결정 · 자동 거절의 {@code evict} 는 그 기억도 지운다.
 */
class ApprovalPreviewOutageTest {

    private static final long APPROVAL_ID = 41L;

    private static final String FINGERPRINT = "fp-1";

    private static OutageRedis redis;

    @BeforeAll
    static void startRedis() {
        redis = OutageRedis.start();
    }

    @AfterAll
    static void stopRedis() {
        redis.close();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Outage.class)
    @DisplayName("Redis 장애 중 같은 입력의 승인 상세를 반복 조회해도 노선 계산(지도 API)은 1번이고, 결정 뒤에는 다시 계산한다")
    void 장애_중_반복_조회는_지도_API_를_다시_부르지_않는다(Outage outage) {
        OutageRedisContext.runner(redis).run(context -> {
            RouteComputationPipeline pipeline = mock(RouteComputationPipeline.class);
            given(pipeline.compute(any())).willReturn(computation());
            RedisApprovalPreviewCache cache = new RedisApprovalPreviewCache(context.getBean(StringRedisTemplate.class),
                    JsonMapper.builder().build(), Clock.systemUTC());
            ApprovalPreviewResolver resolver = new ApprovalPreviewResolver(mock(StopRepository.class), pipeline, cache,
                    mock(ConfirmedRouteRepository.class), mock(RunStopRepository.class), mock(WaypointRepository.class));
            Run run = mock(Run.class);
            given(run.getDepartTime()).willReturn(OffsetDateTime.parse("2026-10-18T08:00:00+09:00"));
            GeoPoint point = new GeoPoint(new BigDecimal("37.5"), new BigDecimal("127.0"));
            OriginDestination originDestination = new OriginDestination(point, point);
            outage.begin(redis);
            try {
                Set<String> tokens = new LinkedHashSet<>();
                for (int view = 1; view <= 10; view++) {
                    tokens.add(resolver.resolvePreview(APPROVAL_ID, FINGERPRINT, mock(DailyRoster.class), originDestination,
                            List.of(), run).preview().token());
                }
                verify(pipeline, times(1)).compute(any());
                assertThat(tokens).as("10번 조회가 받은 토큰 — 같은 입력이면 같은 토큰 하나").hasSize(1);

                cache.evict(APPROVAL_ID);
                resolver.resolvePreview(APPROVAL_ID, FINGERPRINT, mock(DailyRoster.class), originDestination, List.of(), run);
                verify(pipeline, times(2)).compute(any());
            } finally {
                outage.end(redis);
            }
        });
    }

    private static RouteComputation computation() {
        GeoPoint point = new GeoPoint(new BigDecimal("37.501234"), new BigDecimal("127.039876"));
        return new RouteComputation(List.of(OrderedStop.ofStop(11L, 1, point), OrderedStop.ofWaypoint(12L, 2, point)),
                25, new BigDecimal("12.34"),
                List.of(OffsetDateTime.parse("2026-10-18T08:00:00+09:00"), OffsetDateTime.parse("2026-10-18T08:10:00+09:00")),
                List.of(99L), List.of(point),
                new ComputationSnapshot("naver-directions", Map.of("early_minutes", 5), RouteVersionSource.APPROVAL, false));
    }
}
