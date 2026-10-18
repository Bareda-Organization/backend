package src.backend.routing.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.redis.core.StringRedisTemplate;

import tools.jackson.databind.json.JsonMapper;

import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.common.enums.Weekday;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.location.GeoPoint;
import src.backend.global.security.AuthUser;
import src.backend.request.preview.ApprovalPreviewResolver.OriginDestination;
import src.backend.routing.command.WaypointPreviewCache.WaypointPreview;
import src.backend.routing.dto.WaypointRequest;
import src.backend.routing.engine.spec.OrderedStop;
import src.backend.routing.entity.RouteVersionSource;
import src.backend.routing.pipeline.ComputationSnapshot;
import src.backend.routing.pipeline.RouteComputation;
import src.backend.routing.pipeline.RouteComputationPipeline;
import src.backend.routing.entity.RouteVersion;
import src.backend.routing.entity.Waypoint;
import src.backend.routing.pipeline.DailyRoster;
import src.backend.request.assembly.RoutePreviewAssembler;
import src.backend.routing.repository.WaypointRepository;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.RunRepository;
import testsupport.redis.Outage;
import testsupport.redis.OutageRedis;
import testsupport.redis.OutageRedisContext;

/**
 * Redis 가 멈추거나 답하지 않는 동안 경유 지점 미리보기를 읽고 배포하는 호출이 지도 API(노선 계산 파이프라인)를 부르지 않는다(Ruling 919 B ⑤).
 * 미리보기를 못 찾으면 {@code 409 PREVIEW_STALE} 이지 다시 계산이 아니다 — 지도 API 를 부르는 것은 관리자가 미리보기를 요청하는 동작({@code apply=false})뿐이다.
 * 서비스는 진짜, 지도 쪽 파이프라인은 호출 수를 세는 가짜다.
 */
class WaypointPreviewMapCallsOutageTest {

    private static final long RUN_ID = 52L;

    private static final int APPLY_ATTEMPTS = 10;

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
    @DisplayName("Redis 장애 중 경유 지점 미리보기를 못 찾거나 읽어도 지도 API 호출은 0 이고 배포 요청은 409 PREVIEW_STALE")
    void 장애_중_미리보기_읽기와_배포는_지도_API_를_부르지_않는다(Outage outage) {
        OutageRedisContext.runner(redis).run(context -> {
            RouteComputationPipeline pipeline = mock(RouteComputationPipeline.class);
            RunRepository runRepository = mock(RunRepository.class);
            Run run = mock(Run.class);
            OffsetDateTime departAt = OffsetDateTime.now().plusHours(10);
            given(run.getId()).willReturn(RUN_ID);
            given(run.isCanceled()).willReturn(false);
            given(run.getStatus()).willReturn(RunStatus.IDLE);
            given(run.getDepartTime()).willReturn(departAt);
            given(run.getConfirmAt()).willReturn(departAt.minusMinutes(30));
            given(run.getServiceDate()).willReturn(departAt.toLocalDate());
            given(runRepository.findByIdAndAcademyId(RUN_ID, 1L)).willReturn(Optional.of(run));
            RedisWaypointPreviewCache cache = new RedisWaypointPreviewCache(context.getBean(StringRedisTemplate.class),
                    JsonMapper.builder().build(), Clock.systemDefaultZone());
            WaypointCommandService service = new WaypointCommandService(runRepository, mock(WaypointRepository.class), null,
                    pipeline, null, null, cache, null, Clock.systemDefaultZone());
            AuthUser staff = new AuthUser(2L, 1L, Role.STAFF, AccountStatus.ACTIVE, false);
            outage.begin(redis);
            try {
                // 장애 중 미리보기가 만들어졌다 — 인스턴스 안 기억이 받는다
                cache.put(RUN_ID, departAt.toLocalDate(), new WaypointPreview("token-1", 12L, false, 2, "fp-1", 3L, computation()));

                for (int attempt = 0; attempt < APPLY_ATTEMPTS; attempt++) {
                    assertThatThrownBy(() -> service.add(staff, RUN_ID, applyRequest("no-such-token")))
                            .as("토큰이 다른 배포 요청").isInstanceOfSatisfying(BusinessException.class,
                                    e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PREVIEW_STALE));
                    assertThatThrownBy(() -> service.add(staff, RUN_ID, applyRequest("token-1")))
                            .as("미리보기는 읽혔지만 후보 지점이 없는 배포 요청").isInstanceOfSatisfying(BusinessException.class,
                                    e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PREVIEW_STALE));
                }

                verify(pipeline, never()).compute(any());
            } finally {
                outage.end(redis);
            }
        });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Outage.class)
    @DisplayName("대조군 — 관리자가 미리보기를 요청(apply=false)하면 장애 중에도 요청 한 번당 지도 API 호출이 정확히 한 번이다")
    void 미리보기_요청은_한_번에_지도_API_한_번(Outage outage) {
        OutageRedisContext.runner(redis).run(context -> {
            RouteComputationPipeline pipeline = mock(RouteComputationPipeline.class);
            given(pipeline.compute(any())).willReturn(computation());
            Run run = mock(Run.class);
            OffsetDateTime departAt = OffsetDateTime.now().plusHours(10);
            given(run.getId()).willReturn(RUN_ID);
            given(run.isCanceled()).willReturn(false);
            given(run.getStatus()).willReturn(RunStatus.IDLE);
            given(run.getDepartTime()).willReturn(departAt);
            given(run.getConfirmAt()).willReturn(departAt.minusMinutes(30));
            given(run.getServiceDate()).willReturn(departAt.toLocalDate());
            RunRepository runRepository = mock(RunRepository.class);
            given(runRepository.findByIdAndAcademyId(RUN_ID, 1L)).willReturn(Optional.of(run));
            GeoPoint point = new GeoPoint(new BigDecimal("37.5"), new BigDecimal("127.0"));
            RouteContext routeContext = new RouteContext(Weekday.MON, new OriginDestination(point, point), mock(RouteVersion.class),
                    List.of(), mock(DailyRoster.class), List.of());
            RouteContextAssembler assembler = mock(RouteContextAssembler.class);
            given(assembler.contextOf(run)).willReturn(routeContext);
            Waypoint candidate = mock(Waypoint.class);
            given(candidate.getId()).willReturn(12L);
            given(candidate.getLat()).willReturn(new BigDecimal("37.5"));
            given(candidate.getLng()).willReturn(new BigDecimal("127.0"));
            WaypointStore store = mock(WaypointStore.class);
            given(store.saveCandidate(any(), any())).willReturn(candidate);
            RedisWaypointPreviewCache cache = new RedisWaypointPreviewCache(context.getBean(StringRedisTemplate.class),
                    JsonMapper.builder().build(), Clock.systemDefaultZone());
            WaypointCommandService service = new WaypointCommandService(runRepository, mock(WaypointRepository.class),
                    mock(RoutePreviewAssembler.class), pipeline, null, store, cache, assembler, Clock.systemDefaultZone());
            AuthUser staff = new AuthUser(2L, 1L, Role.STAFF, AccountStatus.ACTIVE, false);
            outage.begin(redis);
            try {
                for (int request = 1; request <= 3; request++) {
                    service.add(staff, RUN_ID, previewRequest());
                    verify(pipeline, times(request)).compute(any());
                }
            } finally {
                outage.end(redis);
            }
        });
    }

    private static WaypointRequest previewRequest() {
        return new WaypointRequest(null, new BigDecimal("37.5"), new BigDecimal("127.0"), "경유", null, null, false, null);
    }

    private static WaypointRequest applyRequest(String token) {
        return new WaypointRequest(null, new BigDecimal("37.5"), new BigDecimal("127.0"), "경유", null, null, true, token);
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
