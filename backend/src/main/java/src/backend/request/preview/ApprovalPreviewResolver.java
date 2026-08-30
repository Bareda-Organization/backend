package src.backend.request.preview;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.academy.entity.Academy;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Weekday;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.request.entity.ChangeRequest;
import src.backend.request.entity.ChangeRequestType;
import src.backend.request.preview.spec.ApprovalPreview;
import src.backend.request.preview.spec.ApprovalPreviewCache;
import src.backend.routing.domain.GeoPoint;
import src.backend.routing.entity.RouteStop;
import src.backend.routing.entity.RouteVersionSource;
import src.backend.routing.map.spec.CallerPolicy;
import src.backend.routing.pipeline.ComputationPolicy;
import src.backend.routing.pipeline.DailyRoster;
import src.backend.routing.pipeline.RouteComputation;
import src.backend.routing.pipeline.RouteComputationInput;
import src.backend.routing.pipeline.RouteComputationPipeline;
import src.backend.run.entity.Run;
import src.backend.student.entity.Stop;
import src.backend.student.repository.StopRepository;

/**
 * 승인 상세(§5.5 상세)의 온디맨드 미리보기 계산 오케스트레이션 — 명단·기준점을 만들고, 캐시를 먼저
 * 보고 없을 때만 {@link RouteComputationPipeline} 을 부른다(목표 12 의 호출 수 계약 본체가 여기 있다).
 *
 * <p>같은 계산 코드를 확정 배치({@link src.backend.run.command.RunConfirmationService})와 공유하되
 * 실행 정책만 다르다(ARCHITECTURE §8.3) — 관리자가 화면 앞에서 기다리므로 배치보다 짧은 타임아웃의
 * {@link CallerPolicy#ON_DEMAND} 를 쓴다.
 */
@Component
@RequiredArgsConstructor
public class ApprovalPreviewResolver {

    private static final Duration ON_DEMAND_MAP_TIMEOUT = Duration.ofSeconds(5);

    private final StopRepository stopRepository;
    private final RouteComputationPipeline pipeline;
    private final ApprovalPreviewCache previewCache;

    /**
     * 이 승인 건을 반영했다고 가정한 명단 — 재최적화 "후" 를 계산하는 입력이다.
     *
     * <p>기준선은 {@code run_rider}(확정 배치 산출물에 그동안 승인된 변경까지 반영된 <b>지금</b> 상태)
     * 다 — {@code weekly_address} 를 다시 읽지 않는 이유는 이 회차가 이미 확정돼(Ruling 198) 그
     * 산출물이 이 회차의 진짜 명단이기 때문이다. {@code ABSENT}(결석 처리됨)는 오늘 타지 않는 학생이라
     * 뺀다. 이 변경 요청의 대상 학생만 {@link ChangeRequestType} 에 따라 다르게 가정한다 — 취소는
     * 명단에서 빼고, 승하차지 변경은 새 승하차지로 덮어쓴다.
     */
    public DailyRoster candidateRosterOf(ChangeRequest cr, Run run, Weekday weekday, List<RunRider> riders) {
        List<Long> studentIds = new ArrayList<>();
        Map<Long, Long> stopOverrides = new LinkedHashMap<>();
        for (RunRider rider : riders) {
            if (rider.getStatus() == RiderStatus.ABSENT) {
                continue;
            }
            boolean isTarget = rider.getStudentId().equals(cr.getStudentId());
            if (isTarget && cr.getType() == ChangeRequestType.CANCEL) {
                continue;
            }
            Long stopId = (isTarget && cr.getType() == ChangeRequestType.RELOCATE) ? cr.getNewStopId()
                    : rider.getStopId();
            studentIds.add(rider.getStudentId());
            stopOverrides.put(rider.getStudentId(), stopId);
        }
        return new DailyRoster(run.getAcademyId(), weekday, run.getDirection(), studentIds, stopOverrides);
    }

    /**
     * 출발지·도착지 도출 — {@code RunConfirmationService} 와 같은 규칙이다(Ruling 190): 등원은
     * 첫 승차지→학원, 하원은 학원→마지막 하차지. 온디맨드 미리보기도 배치와 같은 기준점을 써야
     * 전/후 대조가 "같은 구간을 비교한 것" 이 된다 — 기준점이 갈리면 대조 자체가 무의미해진다.
     */
    public OriginDestination originDestinationOf(Academy academy, List<RouteStop> routeStops, Direction direction,
            Long academyId) {
        if (routeStops.isEmpty()) {
            throw new BusinessException(ErrorCode.ROUTE_NOT_CONFIGURED_FOR_RUN);
        }
        List<Long> stopIds = routeStops.stream().map(RouteStop::getStopId).toList();
        Map<Long, Stop> stopsById = stopRepository.findAllByIdInAndAcademyId(stopIds, academyId).stream()
                .collect(Collectors.toMap(Stop::getId, stop -> stop));
        Stop firstStop = stopsById.get(routeStops.get(0).getStopId());
        Stop lastStop = stopsById.get(routeStops.get(routeStops.size() - 1).getStopId());
        if (firstStop == null || lastStop == null) {
            throw new BusinessException(ErrorCode.ROUTE_NOT_CONFIGURED_FOR_RUN);
        }
        GeoPoint academyPoint = new GeoPoint(academy.getLat(), academy.getLng());
        if (direction == Direction.TO_ACADEMY) {
            return new OriginDestination(new GeoPoint(firstStop.getLat(), firstStop.getLng()), academyPoint);
        }
        return new OriginDestination(academyPoint, new GeoPoint(lastStop.getLat(), lastStop.getLng()));
    }

    /**
     * 캐시 조회·계산을 한곳에 모은다 — 지문이 일치하는 캐시가 있으면 파이프라인을 <b>부르지 않고</b>
     * 같은 토큰을 돌려준다(호출 수 검증의 본체, 목표 12). 지문이 어긋나거나 캐시가 없으면 새로
     * 계산하고, <b>기존 값을 대체하는 경우에만</b> {@code stale=true} 를 돌려준다 — 첫 조회는 대체할
     * 낡은 값 자체가 없으므로 낡았다고 말할 수 없다.
     */
    public PreviewResult resolvePreview(Long approvalId, String fingerprint, DailyRoster roster,
            OriginDestination originDestination, Run run) {
        Optional<ApprovalPreview> cached = previewCache.find(approvalId);
        if (cached.isPresent() && cached.get().fingerprint().equals(fingerprint)) {
            return new PreviewResult(cached.get(), false);
        }
        ComputationPolicy policy = new ComputationPolicy(ON_DEMAND_MAP_TIMEOUT, CallerPolicy.ON_DEMAND,
                RouteVersionSource.APPROVAL);
        RouteComputationInput input = new RouteComputationInput(roster, originDestination.origin(),
                originDestination.destination(), List.of(), run.getDepartTime(), policy);
        RouteComputation computation = pipeline.compute(input);
        ApprovalPreview fresh = new ApprovalPreview(UUID.randomUUID().toString(), fingerprint, computation);
        previewCache.put(approvalId, fresh);
        boolean stale = cached.isPresent();
        return new PreviewResult(fresh, stale);
    }

    /** 노선 계산 1회의 출발·도착 기준점 쌍 — {@link RouteComputationInput} 조립 전 임시로 들고 있는다. */
    public record OriginDestination(GeoPoint origin, GeoPoint destination) {
    }

    /** 캐시 조회·계산 결과 — {@code stale} 은 기존 값을 대체한 경우에만 참이다. */
    public record PreviewResult(ApprovalPreview preview, boolean stale) {
    }
}
