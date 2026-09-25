package src.backend.request.query;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
import src.backend.request.domain.ChangeWindow;
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
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.RunRepository;
import src.backend.student.entity.Stop;
import src.backend.student.entity.Student;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentDailyStop;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;

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
 * — 이 클래스는 "무엇을 언제 부르는가" 라는 조회 오케스트레이션만 갖는다(CODE_CONVENTIONS.md §20.2 클래스
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

    private final WeeklyAddressRepository weeklyAddressRepository;

    private final AcademyRepository academyRepository;

    private final RouteRepository routeRepository;

    private final RouteStopRepository routeStopRepository;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RouteVersionRepository routeVersionRepository;

    private final RunStopRepository runStopRepository;

    private final RoutePreviewAssembler routePreviewAssembler;

    private final ApprovalPreviewResolver previewResolver;

    /**
     * 승인 대기 목록(§5.5 목록) — 재최적화를 실행하지 않는다. 저장된 값과 단순 집계만 반환한다.
     *
     * <p>회차·버스·학생·승하차지를 id 목록으로 한 번씩 읽고, 명단은 회차마다 한 번만 읽는다 — 항목마다 따로
     * 읽으면 목록 한 번에 조회가 항목 수에 비례해 는다(BR-075, ARCHITECTURE §14 R3).
     */
    public ApprovalListResponse list(AuthUser requester, ChangeRequestStatus status) {
        List<ChangeRequest> requests = changeRequestRepository
                .findAllByAcademyIdAndStatusAndWindowSegmentOrderByRequestedAtAsc(requester.academyId(), status,
                        ChangeWindow.APPROVAL_REQUIRED.code());
        Map<Long, Run> runs = runRepository
                .findAllByIdInAndAcademyId(requests.stream().map(ChangeRequest::getRunId).distinct().toList(), requester.academyId())
                .stream().collect(Collectors.toMap(Run::getId, run -> run));
        Map<Long, List<RunRider>> ridersByRun = new HashMap<>();
        for (Long runId : runs.keySet()) {
            ridersByRun.put(runId, runRiderRepository.findAllByRunIdAndAcademyId(runId, requester.academyId()));
        }
        Map<Long, String> busNos = busRepository
                .findAllByAcademyIdAndIdIn(requester.academyId(), runs.values().stream().map(Run::getBusId).distinct().toList())
                .stream().collect(Collectors.toMap(Bus::getId, Bus::getBusNo));
        Map<Long, String> studentNames = studentRepository
                .findAllByAcademyIdAndIdIn(requester.academyId(), requests.stream().map(ChangeRequest::getStudentId).distinct()
                        .toList())
                .stream().collect(Collectors.toMap(Student::getId, Student::getName));
        Map<Long, Stop> stops = stopsOf(requests, ridersByRun.values(), requester.academyId());

        List<ApprovalSummaryResponse> items = requests.stream().map(cr -> {
            Run run = Optional.ofNullable(runs.get(cr.getRunId()))
                    .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
            String busNo = Optional.ofNullable(busNos.get(run.getBusId()))
                    .orElseThrow(() -> new BusinessException(ErrorCode.BUS_NOT_FOUND));
            String studentName = Optional.ofNullable(studentNames.get(cr.getStudentId()))
                    .orElseThrow(() -> new BusinessException(ErrorCode.STUDENT_NOT_FOUND));
            return toSummary(cr, run, ridersByRun.get(run.getId()), busNo, studentName, stops, requester.academyId());
        }).toList();
        long pendingCount = changeRequestRepository.countByAcademyIdAndStatus(requester.academyId(), ChangeRequestStatus.PENDING);
        return ApprovalListResponse.of(items, pendingCount);
    }

    /** 목록이 표시할 승하차지 전부 — 명단의 승하차지와 이동 요청의 목적지를 한 번에 읽는다(BR-075). */
    private Map<Long, Stop> stopsOf(List<ChangeRequest> requests, Collection<List<RunRider>> riders,
            Long academyId) {
        Set<Long> stopIds = new HashSet<>();
        riders.forEach(list -> list.forEach(rider -> stopIds.add(rider.getStopId())));
        requests.stream().map(ChangeRequest::getNewStopId).filter(Objects::nonNull).forEach(stopIds::add);
        return stopRepository.findAllByAcademyIdAndIdIn(academyId, List.copyOf(stopIds)).stream()
                .collect(Collectors.toMap(Stop::getId, stop -> stop));
    }

    /**
     * 승인 대기 상세(§5.5 상세) — {@code PENDING} 건만 이 시점에 재최적화를 1회 실행한다. 입력(명단·
     * 승하차지)이 그대로인 채 다시 부르면 캐시가 같은 {@code preview_token} 을 돌려주고 계산은 다시
     * 돌지 않는다. 이미 결정된 건({@code approved}·{@code rejected}·{@code auto_rejected})은
     * {@link #decidedDetailOf} 로 넘겨 재최적화를 건너뛴다 — "승인되면 무엇이 바뀌는가" 라는 질문
     * 자체가 성립하지 않고(그 메서드 javadoc 참고), 이 재최적화 경로에 있던 {@code Route} 조회가
     * 결정된 건에서 실제로 {@code 422 ROUTE_NOT_CONFIGURED_FOR_RUN} 을 냈던 결함이기도 하다.
     *
     * @throws BusinessException {@code 404 APPROVAL_NOT_FOUND}(대상 없음 · 다른 학원 — 존재 비노출, §1.5 · BR-133) ·
     *                            {@code 409 RUN_NOT_CONFIRMED}({@code PENDING} 건인데 그 회차가 아직
     *                            {@code idle} 일 때만 — ②구간 판정({@code ChangeWindowPolicy})은
     *                            {@code confirmAt} 도래 즉시 서는데, 실제 확정은 30초 폴링 배치가
     *                            돌아야 반영되어 그 사이에 신청이 들어오면 이 경로를 탄다) ·
     *                            {@code 422 ROUTE_NOT_CONFIGURED_FOR_RUN}({@code PENDING} 건인데
     *                            그 회차의 고정 노선이 없을 때만 — 결정된 건은 이 경로를 타지 않는다)
     */
    public ApprovalDetailResponse detail(AuthUser requester, Long approvalId) {
        ChangeRequest cr = changeRequestRepository.findByIdAndAcademyId(approvalId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.APPROVAL_NOT_FOUND));
        Long academyId = cr.getAcademyId();

        Run run = runRepository.findById(cr.getRunId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        Weekday weekday = weekdayOf(run.getServiceDate());
        List<RunRider> riders = runRiderRepository.findAllByRunIdAndAcademyId(run.getId(), academyId);
        ApprovalSummaryResponse summary = toSummary(cr, run, riders, academyId);

        Bus bus = busRepository.findByIdAndAcademyId(run.getBusId(), academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BUS_NOT_FOUND));
        if (cr.getStatus() != ChangeRequestStatus.PENDING) {
            return decidedDetailOf(summary, run, riders, bus);
        }
        // ②구간 승인 대기 신청은 confirmAt 도래 즉시(ChangeWindowPolicy) 성립하지만, 회차의 실제
        // idle → confirmed 전이와 그때 함께 만들어지는 confirmed_route 는 30초 폴링 확정 배치가
        // 돌아야 반영된다(RunConfirmationScheduler). 그 사이 창에서 이 조회가 들어오면 아래
        // ConfirmedRoute 조회가 항상 비어 있어 원래는 원시 IllegalStateException(비구조 500)이
        // 났다 — RosterQueryService.managerRoster 와 같은 경계에서 같은 코드로 막는다.
        if (run.getStatus() == RunStatus.IDLE) {
            throw new BusinessException(ErrorCode.RUN_NOT_CONFIRMED);
        }

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
        List<FixedStop> fixedStops = previewResolver.fixedStopsOf(run, roster);
        String fingerprint = RunConfirmationFingerprint.of(academyId, weekday, run.getDirection(),
                run.getDepartTime(), originDestination.origin(), originDestination.destination(),
                roster.stopOverrides(), fixedStops);

        PreviewResult previewResult = previewResolver.resolvePreview(approvalId, fingerprint, roster,
                originDestination, fixedStops, run);
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
                routePreviewAssembler.removedOf(beforeSeq, afterSeq, stopsById),
                routePreviewAssembler.roadPathOrEmpty(currentVersion.getRoadPath()), computation.roadPath());

        ApprovalCapacityResponse capacity = new ApprovalCapacityResponse(bus.getStudentCapacity(),
                roster.studentIds().size());

        List<AffectedStudentResponse> affectedStudents = routePreviewAssembler.affectedStudentsOf(academyId,
                cr.getStudentId(),
                summary.studentName(), riders, beforeSeq, afterSeq);

        return ApprovalDetailResponse.of(summary, routePreview, run.getDepartTime(),
                routePreviewAssembler.lastEtaOf(stopsBefore), routePreviewAssembler.lastEtaOf(stopsAfter),
                currentVersion.getEstDistanceKm(), computation.estDistanceKm(), currentVersion.getEstDurationMin(),
                computation.estDurationMin(), affectedStudents, capacity, previewResult.preview().token(),
                previewResult.stale());
    }

    /**
     * 결정이 끝난 건의 상세(§5.5 상세 "왜 결정된 건은 비는가", 2026-09-14 Ruling 265 로 정본에
     * 명시됨 — 재최적화·경유지 조회를 전혀 하지 않는다).
     * {@code route_preview}·{@code est_time_*}·{@code est_distance_*}·{@code preview_token}·
     * {@code preview_stale} 은 전부 "이 건이 승인되면 무엇이 바뀌는가" 를 답하는 필드인데, 결정이
     * 이미 끝난 건에는 그 질문 자체가 성립하지 않는다 — {@code null}(배열은 빈 배열)로 채운다.
     * {@code capacity} 만 예외다: "지금 이 버스에 몇 명이 타는가" 는 결정 여부와 무관하게 항상 답할
     * 수 있는 질문이라, 이 변경을 가정한 후보 명단이 아니라 지금 실제 탑승 인원({@code ABSENT} 제외)
     * 으로 채운다.
     *
     * <p>{@code preview_token} 을 비우는 것은 재결정을 막기 위해서가 <b>아니다</b> —
     * {@code ChangeRequestDecisionService.decide} 는 캐시·토큰을 보기도 전에
     * {@link ChangeRequest#assertPending()} 을 먼저 거치므로(그 서비스의 클래스·메서드 javadoc
     * 확인 완료), 결정된 건은 어떤 토큰을 들고 와도 그 자리에서 {@code 409 APPROVAL_ALREADY_DECIDED}
     * 로 막힌다. 여기서 토큰을 비우는 이유는 ①토큰을 만들려면 이 메서드가 건너뛴 재최적화를 다시
     * 돌려야 하고 ②그 재최적화가 이 결함의 원인이던 {@code Route} 조회를 다시 태우며(§ 위
     * {@link #detail} javadoc) ③결정된 건에 유효해 보이는 토큰을 주면 화면에 "다시 결정할 수 있다"
     * 는 인상을 줄 수 있어서다(보고서 후속 절 참고).
     */
    private ApprovalDetailResponse decidedDetailOf(ApprovalSummaryResponse summary, Run run, List<RunRider> riders,
            Bus bus) {
        long assigned = riders.stream().filter(r -> r.getStatus() != RiderStatus.ABSENT).count();
        ApprovalCapacityResponse capacity = new ApprovalCapacityResponse(bus.getStudentCapacity(), (int) assigned);
        return ApprovalDetailResponse.of(summary, null, run.getDepartTime(), null, null, null, null, null, null,
                List.of(), capacity, null, false);
    }

    /** 요약 1건 — 목록(§5.5 목록)이 회차·명단을 매번 새로 읽어야 할 때 쓰는 얕은 진입점. */
    /** 상세(§5.5 상세)용 요약 — 한 건이라 버스·학생·승하차지를 그 자리에서 읽는다. */
    private ApprovalSummaryResponse toSummary(ChangeRequest cr, Run run, List<RunRider> riders, Long academyId) {
        Bus bus = busRepository.findByIdAndAcademyId(run.getBusId(), academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BUS_NOT_FOUND));
        Student student = studentRepository.findById(cr.getStudentId())
                .orElseThrow(() -> new BusinessException(ErrorCode.STUDENT_NOT_FOUND));
        return toSummary(cr, run, riders, bus.getBusNo(), student.getName(),
                stopsOf(List.of(cr), List.of(riders), academyId), academyId);
    }

    private ApprovalSummaryResponse toSummary(ChangeRequest cr, Run run, List<RunRider> riders, String busNo,
            String studentName, Map<Long, Stop> stops, Long academyId) {
        Optional<RunRider> mine = riders.stream()
                .filter(r -> r.getStudentId().equals(cr.getStudentId()))
                .findFirst();
        SubjectStop subject = mine.isPresent()
                ? liveSubjectStopOf(mine.get(), cr, riders, stops)
                : decidedSubjectStopOf(cr, run, riders, stops, academyId);
        return ApprovalSummaryResponse.of(cr, studentName, busNo, run.getDirection(),
                cr.getDeadlineAt(), subject.stopName(), subject.remainingRiders(), subject.willRemoveStop());
    }

    private SubjectStop liveSubjectStopOf(RunRider mine, ChangeRequest cr, List<RunRider> riders,
            Map<Long, Stop> stops) {
        Stop stop = Optional.ofNullable(stops.get(mine.getStopId()))
                .orElseThrow(() -> new IllegalStateException("승하차지가 없다 — stopId=" + mine.getStopId()));
        long remaining = riders.stream()
                .filter(r -> !r.getStudentId().equals(cr.getStudentId()))
                .filter(r -> mine.getStopId().equals(r.getStopId()))
                .filter(r -> r.getStatus() != RiderStatus.ABSENT)
                .count();
        return new SubjectStop(stop.getName(), (int) remaining, remaining == 0);
    }

    /**
     * 이 학생이 지금 그 회차 명단에 없는 경우 — {@code rejected}·{@code auto_rejected} 는 이것이
     * 정상이다({@code reject}·{@code autoReject} 는 {@code RunRider} 를 건드리지 않는다). API_SPEC
     * §5.5 목록의 "명단에 없을 때" 표(2026-09-12 Ruling 265)가 이 상황과 아래 우선순위를 정본에
     * 명시한다 — "이 학생이 지금 타고 있는 승하차지를 비우면 어떻게 되는가" 라는 질문 자체가 성립하지
     * 않으므로, 저장된 값만으로 "이 요청이 가리키던 승하차지" 를 최선으로 되짚는다(§5.5 가 명시한
     * "재최적화 없이 저장된 값·단순 집계만" 취지를 따름).
     *
     * <p>우선순위 — ① RELOCATE 의 확정된 목적지({@code new_stop_id}) ② 그 요일·방향의 등록 주소
     * ({@code weekly_address}, CANCEL 포함 전 유형을 커버) ③ RELOCATE 의 미확정 주소 원문
     * ({@code new_address}, {@code Stop} 조회는 안 되므로 표시용 문자열 그대로) ④ 그마저 없으면
     * 플레이스홀더.
     *
     * <p>결정이 이미 끝난 건이라 "승인 시 제거되는가" 자체가 성립하지 않는다 — {@code will_remove_stop}
     * 은 이 경로에서 항상 {@code false}. 상태값으로 분기하지 않고 "명단에 있는가" 로만 가른 이유는
     * {@code approved} 도 구조적으로만 명단에 있는 것이 보장될 뿐이라, 가정이 깨지는 경우(예: 향후
     * 다른 승인 경로가 추가돼 명단을 지우는 경우)에도 이 경로가 방어선이 되게 하기 위함이다.
     */
    private SubjectStop decidedSubjectStopOf(ChangeRequest cr, Run run, List<RunRider> riders,
            Map<Long, Stop> stops, Long academyId) {
        Long resolvedStopId = cr.getNewStopId();
        if (resolvedStopId == null) {
            Weekday weekday = weekdayOf(run.getServiceDate());
            resolvedStopId = weeklyAddressRepository
                    .findDailyStops(academyId, List.of(cr.getStudentId()), weekday, run.getDirection())
                    .stream()
                    .map(StudentDailyStop::getStopId)
                    .findFirst()
                    .orElse(null);
        }
        if (resolvedStopId != null) {
            // 요일별 주소에서 찾은 승하차지는 명단·이동 목적지에 없을 수 있어 그때만 따로 읽는다.
            Long lookupId = resolvedStopId;
            Optional<Stop> stop = Optional.ofNullable(stops.get(lookupId))
                    .or(() -> stopRepository.findAllByAcademyIdAndIdIn(academyId, List.of(lookupId)).stream()
                            .findFirst());
            if (stop.isPresent()) {
                Long stopId = resolvedStopId;
                long remaining = riders.stream()
                        .filter(r -> stopId.equals(r.getStopId()))
                        .filter(r -> r.getStatus() != RiderStatus.ABSENT)
                        .count();
                return new SubjectStop(stop.get().getName(), (int) remaining, false);
            }
        }
        if (cr.getNewAddress() != null) {
            return new SubjectStop(cr.getNewAddress(), 0, false);
        }
        return new SubjectStop("배정 정보 없음", 0, false);
    }

    /** {@link #liveSubjectStopOf}·{@link #decidedSubjectStopOf} 가 채우는 3필드 묶음(§5.5 목록 필수). */
    private record SubjectStop(String stopName, int remainingRiders, boolean willRemoveStop) {
    }

    /**
     * 그 날짜의 요일 — {@code RunConfirmationService.weekdayOf} 와 같은 계산(중복 헬퍼 관례, 그
     * 클래스 javadoc 참고). {@code LocalDate} 자체가 요일을 들고 있으므로 시계를 보지 않는다.
     */
    private Weekday weekdayOf(LocalDate serviceDate) {
        return Weekday.valueOf(serviceDate.getDayOfWeek().name().substring(0, 3).toUpperCase(Locale.ROOT));
    }
}
