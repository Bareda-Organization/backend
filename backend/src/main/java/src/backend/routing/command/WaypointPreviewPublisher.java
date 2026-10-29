package src.backend.routing.command;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.routing.command.WaypointPreviewCache.WaypointPreview;
import src.backend.routing.dto.WaypointResponse;
import src.backend.routing.entity.RouteVersionSource;
import src.backend.routing.map.spec.CallerPolicy;
import src.backend.routing.pipeline.ComputationPolicy;
import src.backend.routing.pipeline.RouteComputation;
import src.backend.routing.pipeline.RouteComputationInput;
import src.backend.routing.pipeline.RouteComputationPipeline;
import src.backend.run.domain.RunConfirmationFingerprint;

/**
 * 경유 지점 변경의 재최적화 미리보기 계산과 그 배포 — {@link RouteComputationPipeline#compute} 가 외부 지도 API 를 부르므로
 * 트랜잭션 밖에서 불리고, 배포 저장만 {@link WaypointStore} 의 짧은 트랜잭션에 맡긴다. 이 클래스에는 {@code @Transactional} 이 없다.
 */
@Component
@RequiredArgsConstructor
public class WaypointPreviewPublisher {

    /** 온디맨드(관리자가 화면 앞에서 대기) 지도 API 타임아웃 — {@code ApprovalPreviewResolver} 와 같은 값. */
    private static final Duration ON_DEMAND_MAP_TIMEOUT = Duration.ofSeconds(5);

    private final RouteComputationPipeline pipeline;
    private final WaypointStore waypointStore;
    private final WaypointPreviewCache previewCache;
    private final WaypointResponseAssembler responseAssembler;
    private final Clock clock;

    /** 재최적화를 계산하고(트랜잭션 밖 — 외부 지도 API) 미리보기로 남긴다. 확정 노선은 그대로다. */
    public WaypointResponse preview(WaypointChange change, int seq, boolean removal) {
        RouteContext ctx = change.ctx();
        ComputationPolicy policy = new ComputationPolicy(ON_DEMAND_MAP_TIMEOUT, CallerPolicy.ON_DEMAND,
                RouteVersionSource.WAYPOINT);
        RouteComputationInput input = new RouteComputationInput(ctx.roster(), ctx.originDestination().origin(),
                ctx.originDestination().destination(), change.fixedStops(), change.run().getDepartTime(), policy);
        RouteComputation computation = pipeline.compute(input);
        String token = UUID.randomUUID().toString();
        previewCache.put(change.run().getId(), change.run().getServiceDate(), new WaypointPreview(token,
                change.waypoint().getId(), removal, seq, fingerprintOf(change), ctx.currentVersion().getId(),
                computation));
        return responseAssembler.responseOf(change, computation, false, token);
    }

    /**
     * 미리보기의 계산을 그대로 배포한다 — 그 사이 입력(명단·승하차지·경유 지점)이 바뀌었으면 지문이 어긋나
     * {@code 409 PREVIEW_STALE} 이다(승인 배포 {@code ChangeRequestDecisionService.assertFingerprintFresh} 와 같은 형태).
     */
    public WaypointResponse deploy(WaypointChange change, WaypointPreview preview, Long accountId) {
        if (!fingerprintOf(change).equals(preview.fingerprint())) {
            throw new BusinessException(ErrorCode.PREVIEW_STALE);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (preview.removal()) {
            waypointStore.deployRemoval(change.run(), change.waypoint(), preview, accountId, now);
        } else {
            waypointStore.deployAdd(change.run(), change.waypoint(), preview, accountId, now);
        }
        previewCache.evict(change.run().getId());
        return responseAssembler.responseOf(change, preview.computation(), true, null);
    }

    private static String fingerprintOf(WaypointChange change) {
        RouteContext ctx = change.ctx();
        return RunConfirmationFingerprint.of(change.run().getAcademyId(), ctx.weekday(), change.run().getDirection(),
                change.run().getDepartTime(), ctx.originDestination().origin(), ctx.originDestination().destination(),
                ctx.roster().stopOverrides(), change.fixedStops());
    }
}
