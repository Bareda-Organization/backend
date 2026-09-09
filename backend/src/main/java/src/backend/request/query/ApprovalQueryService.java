package src.backend.request.query;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Weekday;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.global.security.access.AcademyScope;
import src.backend.request.dto.AffectedStudentResponse;
import src.backend.request.dto.ApprovalCapacityResponse;
import src.backend.request.dto.ApprovalDetailResponse;
import src.backend.request.dto.ApprovalListResponse;
import src.backend.request.dto.ApprovalSummaryResponse;
import src.backend.request.dto.PreviewStopResponse;
import src.backend.request.dto.RoutePreviewResponse;
import src.backend.request.entity.ChangeRequest;
import src.backend.request.entity.ChangeRequestStatus;
import src.backend.request.preview.ApprovalPreviewResolver;
import src.backend.request.preview.ApprovalPreviewResolver.OriginDestination;
import src.backend.request.preview.ApprovalPreviewResolver.PreviewResult;
import src.backend.request.repository.ChangeRequestRepository;
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
import src.backend.run.repository.RunRepository;
import src.backend.student.entity.Stop;
import src.backend.student.entity.Student;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 승인 대기 목록·상세 조회(API_SPEC §5.5) — 목록은 저장된 값만 집계하고 재최적화를 실행하지 않는다.
 * 상세는 이 시점에 재최적화를 <b>1회</b> 실행해 전/후 대조를 산출한다.
 *
 * <p>⚠ <b>목록·상세를 가른 이유</b>(API_SPEC §5.5) — 재최적화는 계산 비용이 크다. 목록 항목마다
 * 계산하면 대기 건이 N개일 때 한 번의 목록 조회에 N회 최적화가 동기로 실행되어 응답이 지연된다.
 * 승인 화면은 한 건씩 열므로 목록은 요약만, 대조는 상세에서 1건만 계산한다(ARCHITECTURE §8.4).
 *
 * <p>온디맨드 미리보기 계산(캐시 조회·재최적화 1회 실행)은 {@link ApprovalPreviewResolver} 에,
 * 전/후 노선 대조 조립(정차 목록·순번 비교·영향 학생 산출)은 {@link RoutePreviewAssembler} 에 위임한다
 * — 이 클래스는 "무엇을 언제 부르는가" 라는 조회 오케스트레이션만 갖는다(reference.md §20.2 클래스
 * 크기 관례에 따른 분리이며, 값 조립·계산 로직 자체는 바뀌지 않았다).
 */
@Service
@RequiredArgsConstructor
public class ApprovalQueryService {

    private final ChangeRequestRepository changeRequestRepository;

    private final RunRepository runRepository;

    private final BusRepository busRepository;

    private final StudentRepository studentRepository;

    private final StopRepository stopRepository;

    private final RunRiderRepository runRiderRepository;

    private final AcademyRepository academyRepository;

    private final RouteRepository routeRepository;

    private final RouteStopRepository routeStopRepository;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RouteVersionRepository routeVersionRepository;

    private final RunStopRepository runStopRepository;

    private final RoutePreviewAssembler routePreviewAssembler;

    private final ApprovalPreviewResolver previewResolver;

    /** 승인 대기 목록(§5.5 목록) — 재최적화를 실행하지 않는다. 저장된 값과 단순 집계만 반환한다. */
    public ApprovalListResponse list(AuthUser requester, ChangeRequestStatus status) {
        List<ChangeRequest> requests = changeRequestRepository
                .findAllByAcademyIdAndStatusOrderByRequestedAtAsc(requester.academyId(), status);
        List<ApprovalSummaryResponse> items = requests.stream()
                .map(cr -> toSummary(cr, requester.academyId()))
                .toList();
        long pendingCount = changeRequestRepository.countByAcademyIdAndStatus(requester.academyId(),
                ChangeRequestStatus.PENDING);
        return ApprovalListResponse.of(items, pendingCount);
    }

    /**
     * 승인 대기 상세(§5.5 상세) — 이 시점에 재최적화를 1회 실행한다. 입력(명단·승하차지)이 그대로인 채
     * 다시 부르면 캐시가 같은 {@code preview_token} 을 돌려주고 계산은 다시 돌지 않는다.
     *
     * @throws BusinessException {@code 404 APPROVAL_NOT_FOUND}(대상 없음) ·
     *                            {@code 403 ACADEMY_SCOPE_VIOLATION}(다른 학원 소속)
     */
    public ApprovalDetailResponse detail(AuthUser requester, Long approvalId) {
        ChangeRequest cr = changeRequestRepository.findById(approvalId)
                .orElseThrow(() -> new BusinessException(ErrorCode.APPROVAL_NOT_FOUND));
        AcademyScope.assertAccessible(requester, cr.getAcademyId());
        Long academyId = cr.getAcademyId();

        Run run = runRepository.findById(cr.getRunId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        Weekday weekday = weekdayOf(run.getServiceDate());
        List<RunRider> riders = runRiderRepository.findAllByRunIdAndAcademyId(run.getId(), academyId);
        ApprovalSummaryResponse summary = toSummary(cr, run, riders, academyId);

        Academy academy = academyRepository.findById(academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));
        if (!academy.hasCoordinates()) {
            throw new BusinessException(ErrorCode.ACADEMY_COORDINATES_MISSING);
        }
        Route route = routeRepository
                .findByAcademyIdAndBusIdAndWeekdayAndDirection(academyId, run.getBusId(), weekday, run.getDirection())
                .orElseThrow(() -> new BusinessException(ErrorCode.ROUTE_NOT_CONFIGURED_FOR_RUN));
        List<RouteStop> routeStops = routeStopRepository.findAllOrderedByRouteIdAndAcademyId(route.getId(), academyId);
        OriginDestination originDestination = previewResolver.originDestinationOf(academy, routeStops,
                run.getDirection(), academyId);

        DailyRoster roster = previewResolver.candidateRosterOf(cr, run, weekday, riders);
        String fingerprint = RunConfirmationFingerprint.of(academyId, weekday, run.getDirection(),
                run.getDepartTime(), originDestination.origin(), originDestination.destination(),
                roster.stopOverrides(), List.of());

        PreviewResult previewResult = previewResolver.resolvePreview(approvalId, fingerprint, roster,
                originDestination, run);
        RouteComputation computation = previewResult.preview().computation();

        ConfirmedRoute confirmedRoute = confirmedRouteRepository.findById(run.getId())
                .orElseThrow(() -> new IllegalStateException("확정 노선이 없다 — runId=" + run.getId()));
        Long currentVersionId = confirmedRoute.getCurrentVersionId();
        RouteVersion currentVersion = routeVersionRepository.findById(currentVersionId)
                .orElseThrow(() -> new IllegalStateException("노선 버전이 없다 — versionId=" + currentVersionId));
        List<RunStop> beforeRunStops = runStopRepository
                .findAllByRouteVersionIdAndAcademyIdOrderBySeq(currentVersionId, academyId);

        Map<Long, Stop> stopsById = routePreviewAssembler.stopsByIdOf(
                routePreviewAssembler.unionOfStopIds(beforeRunStops, computation), academyId);
        List<PreviewStopResponse> stopsBefore = routePreviewAssembler.toPreviewStopsFromRunStops(beforeRunStops,
                stopsById);
        List<PreviewStopResponse> stopsAfter = routePreviewAssembler.toPreviewStopsFromComputation(computation,
                stopsById);
        Map<Long, Integer> beforeSeq = routePreviewAssembler.seqMapOfRunStops(beforeRunStops);
        Map<Long, Integer> afterSeq = routePreviewAssembler.seqMapOfComputation(computation);

        RoutePreviewResponse routePreview = RoutePreviewResponse.of(stopsBefore, stopsAfter,
                routePreviewAssembler.reorderedOf(beforeSeq, afterSeq, stopsById),
                routePreviewAssembler.removedOf(beforeSeq, afterSeq, stopsById));

        Bus bus = busRepository.findByIdAndAcademyId(run.getBusId(), academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BUS_NOT_FOUND));
        ApprovalCapacityResponse capacity = new ApprovalCapacityResponse(bus.getStudentCapacity(),
                roster.studentIds().size());

        List<AffectedStudentResponse> affectedStudents = routePreviewAssembler.affectedStudentsOf(cr.getStudentId(),
                summary.studentName(), riders, beforeSeq, afterSeq);

        return ApprovalDetailResponse.of(summary, routePreview, routePreviewAssembler.lastEtaOf(stopsBefore),
                routePreviewAssembler.lastEtaOf(stopsAfter), currentVersion.getEstDistanceKm(),
                computation.estDistanceKm(), affectedStudents, capacity, previewResult.preview().token(),
                previewResult.stale());
    }

    /** 요약 1건 — 목록(§5.5 목록)이 회차·명단을 매번 새로 읽어야 할 때 쓰는 얕은 진입점. */
    private ApprovalSummaryResponse toSummary(ChangeRequest cr, Long academyId) {
        Run run = runRepository.findById(cr.getRunId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        List<RunRider> riders = runRiderRepository.findAllByRunIdAndAcademyId(run.getId(), academyId);
        return toSummary(cr, run, riders, academyId);
    }

    /**
     * 요약 1건 — 상세(§5.5 상세)가 이미 읽어 둔 회차·명단을 그대로 넘겨 재조회를 피할 때 쓴다.
     *
     * <p>{@code remainingRiders}·{@code willRemoveStop} 은 이 학생을 뺀 뒤 같은 승하차지에 남는
     * {@code run_rider} 행 수만 세면 나온다 — 재최적화 없이 저장된 값의 집계만으로 답한다(§5.5 목록).
     */
    private ApprovalSummaryResponse toSummary(ChangeRequest cr, Run run, List<RunRider> riders, Long academyId) {
        Bus bus = busRepository.findByIdAndAcademyId(run.getBusId(), academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BUS_NOT_FOUND));
        Student student = studentRepository.findById(cr.getStudentId())
                .orElseThrow(() -> new BusinessException(ErrorCode.STUDENT_NOT_FOUND));
        RunRider mine = riders.stream()
                .filter(r -> r.getStudentId().equals(cr.getStudentId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "승인 대기 학생이 회차 명단에 없다 — runId=" + run.getId() + ", studentId=" + cr.getStudentId()));
        Stop stop = stopRepository.findAllByAcademyIdAndIdIn(academyId, List.of(mine.getStopId())).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("승하차지가 없다 — stopId=" + mine.getStopId()));
        long remaining = riders.stream()
                .filter(r -> !r.getStudentId().equals(cr.getStudentId()))
                .filter(r -> mine.getStopId().equals(r.getStopId()))
                .filter(r -> r.getStatus() != RiderStatus.ABSENT)
                .count();
        return ApprovalSummaryResponse.of(cr, student.getName(), bus.getBusNo(), run.getDirection(),
                cr.getDeadlineAt(), stop.getName(), (int) remaining, remaining == 0);
    }

    /**
     * 그 날짜의 요일 — {@code RunConfirmationService.weekdayOf} 와 같은 계산(중복 헬퍼 관례, 그
     * 클래스 javadoc 참고). {@code LocalDate} 자체가 요일을 들고 있으므로 시계를 보지 않는다.
     */
    private Weekday weekdayOf(LocalDate serviceDate) {
        return Weekday.valueOf(serviceDate.getDayOfWeek().name().substring(0, 3).toUpperCase(Locale.ROOT));
    }
}
