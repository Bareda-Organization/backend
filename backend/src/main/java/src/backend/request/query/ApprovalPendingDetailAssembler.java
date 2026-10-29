package src.backend.request.query;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.boarding.entity.RunRider;
import src.backend.bus.entity.Bus;
import src.backend.global.common.enums.Weekday;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.request.assembly.RoutePreviewAssembler;
import src.backend.request.dto.AffectedStudentResponse;
import src.backend.request.dto.ApprovalCapacityResponse;
import src.backend.request.dto.ApprovalDetailResponse;
import src.backend.request.dto.ApprovalSummaryResponse;
import src.backend.request.dto.PreviewStopResponse;
import src.backend.request.dto.RoutePreviewResponse;
import src.backend.request.entity.ChangeRequest;
import src.backend.request.preview.ApprovalPreviewResolver;
import src.backend.request.preview.ApprovalPreviewResolver.OriginDestination;
import src.backend.request.preview.ApprovalPreviewResolver.PreviewResult;
import src.backend.routing.engine.spec.FixedStop;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.Route;
import src.backend.routing.entity.RouteStop;
import src.backend.routing.entity.RouteVersion;
import src.backend.routing.entity.RunStop;
import src.backend.routing.pipeline.DailyRoster;
import src.backend.routing.pipeline.RouteComputation;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.domain.RunConfirmationFingerprint;
import src.backend.run.entity.Run;
import src.backend.student.entity.Stop;

/**
 * 결정 대기({@code PENDING}) 건의 승인 상세 조립 — 재최적화를 1회 실행해 확정 노선과의 전/후 대조를 만든다.
 * 미리보기 계산(캐시 조회·재최적화)은 {@link ApprovalPreviewResolver}, 대조 값 조립은 {@link RoutePreviewAssembler}
 * 에 위임하고, 이 클래스는 "무엇을 언제 부르는가" 만 갖는다.
 */
@Component
@RequiredArgsConstructor
class ApprovalPendingDetailAssembler {

    private final AcademyRepository academyRepository;

    private final RouteRepository routeRepository;

    private final RouteStopRepository routeStopRepository;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RouteVersionRepository routeVersionRepository;

    private final RunStopRepository runStopRepository;

    private final RoutePreviewAssembler routePreviewAssembler;

    private final ApprovalPreviewResolver previewResolver;

    /** 상세 조립에 필요한 요청·회차·명단·버스·요약·결정 묶음. */
    record Subject(ChangeRequest cr, Run run, List<RunRider> riders, Bus bus, ApprovalSummaryResponse summary,
            ApprovalDetailResponse.Decision decision) {
    }

    /** 확정 노선의 현재 버전과 그 정차 목록(변경 전 쪽). */
    private record Before(RouteVersion version, List<RunStop> runStops) {
    }

    /** 변경 전/후 정차 응답과 정차별 순번 맵(대조 값 조립 재료). */
    private record Sides(Map<Long, Stop> stopsById, List<PreviewStopResponse> stopsBefore,
            List<PreviewStopResponse> stopsAfter, Map<Long, Integer> beforeSeq, Map<Long, Integer> afterSeq) {
    }

    /** 이 시점에 재최적화를 실행해 전/후 대조가 채워진 상세를 만든다. */
    ApprovalDetailResponse pendingDetailOf(Long approvalId, Subject subject) {
        ChangeRequest cr = subject.cr();
        Run run = subject.run();
        Long academyId = cr.getAcademyId();
        Weekday weekday = Weekday.of(run.getServiceDate());
        OriginDestination originDestination = originDestinationOf(run, weekday, academyId);

        DailyRoster roster = previewResolver.candidateRosterOf(cr, run, weekday, subject.riders());
        List<FixedStop> fixedStops = previewResolver.fixedStopsOf(run, roster);
        String fingerprint = RunConfirmationFingerprint.of(academyId, weekday, run.getDirection(),
                run.getDepartTime(), originDestination.origin(), originDestination.destination(),
                roster.stopOverrides(), fixedStops);

        PreviewResult previewResult = previewResolver.resolvePreview(approvalId, fingerprint, roster,
                originDestination, fixedStops, run);
        return comparisonOf(subject, academyId, roster.studentIds().size(), previewResult);
    }

    private OriginDestination originDestinationOf(Run run, Weekday weekday, Long academyId) {
        Academy academy = academyRepository.findById(academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));
        if (!academy.hasCoordinates()) {
            throw new BusinessException(ErrorCode.ACADEMY_COORDINATES_MISSING);
        }
        Route route = routeRepository
                .findByAcademyIdAndBusIdAndWeekdayAndDirection(academyId, run.getBusId(), weekday, run.getDirection())
                .orElseThrow(() -> new BusinessException(ErrorCode.ROUTE_NOT_CONFIGURED_FOR_RUN));
        List<RouteStop> routeStops = routeStopRepository.findAllOrderedByRouteIdAndAcademyId(route.getId(), academyId);
        return previewResolver.originDestinationOf(academy, routeStops, run.getDirection(), academyId);
    }

    private ApprovalDetailResponse comparisonOf(Subject subject, Long academyId, int rosterSize,
            PreviewResult previewResult) {
        RouteComputation computation = previewResult.preview().computation();
        Before before = beforeOf(subject.run(), academyId);
        Sides sides = sidesOf(before.runStops(), computation, academyId);
        RouteVersion currentVersion = before.version();

        RoutePreviewResponse routePreview = RoutePreviewResponse.of(sides.stopsBefore(), sides.stopsAfter(),
                routePreviewAssembler.reorderedOf(sides.beforeSeq(), sides.afterSeq(), sides.stopsById()),
                routePreviewAssembler.removedOf(sides.beforeSeq(), sides.afterSeq(), sides.stopsById()),
                routePreviewAssembler.roadPathOrEmpty(currentVersion.getRoadPath()), computation.roadPath());
        ApprovalCapacityResponse capacity = new ApprovalCapacityResponse(subject.bus().getStudentCapacity(),
                rosterSize);
        List<AffectedStudentResponse> affectedStudents = routePreviewAssembler.affectedStudentsOf(academyId,
                subject.cr().getStudentId(), subject.summary().studentName(), subject.riders(), sides.beforeSeq(),
                sides.afterSeq());

        return ApprovalDetailResponse.of(subject.summary(), routePreview, subject.run().getDepartTime(),
                routePreviewAssembler.lastEtaOf(sides.stopsBefore()), routePreviewAssembler.lastEtaOf(sides.stopsAfter()),
                currentVersion.getEstDistanceKm(), computation.estDistanceKm(), currentVersion.getEstDurationMin(),
                computation.estDurationMin(), affectedStudents, capacity, previewResult.preview().token(),
                previewResult.stale(), subject.decision());
    }

    private Before beforeOf(Run run, Long academyId) {
        ConfirmedRoute confirmedRoute = confirmedRouteRepository.findById(run.getId())
                .orElseThrow(() -> new IllegalStateException("확정 노선이 없다 — runId=" + run.getId()));
        Long currentVersionId = confirmedRoute.getCurrentVersionId();
        RouteVersion currentVersion = routeVersionRepository.findById(currentVersionId)
                .orElseThrow(() -> new IllegalStateException("노선 버전이 없다 — versionId=" + currentVersionId));
        List<RunStop> beforeRunStops = runStopRepository
                .findAllByRouteVersionIdAndAcademyIdOrderBySeq(currentVersionId, academyId);
        return new Before(currentVersion, beforeRunStops);
    }

    private Sides sidesOf(List<RunStop> beforeRunStops, RouteComputation computation, Long academyId) {
        Map<Long, Stop> stopsById = routePreviewAssembler.stopsByIdOf(
                routePreviewAssembler.unionOfStopIds(beforeRunStops, computation), academyId);
        List<PreviewStopResponse> stopsBefore = routePreviewAssembler.toPreviewStopsFromRunStops(beforeRunStops,
                stopsById);
        List<PreviewStopResponse> stopsAfter = routePreviewAssembler.toPreviewStopsFromComputation(computation,
                stopsById);
        return new Sides(stopsById, stopsBefore, stopsAfter, routePreviewAssembler.seqMapOfRunStops(beforeRunStops),
                routePreviewAssembler.seqMapOfComputation(computation));
    }

}
