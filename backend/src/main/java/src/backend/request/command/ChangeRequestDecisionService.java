package src.backend.request.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.global.security.access.AcademyScope;
import src.backend.request.domain.ChangeWindow;
import src.backend.request.domain.ChangeWindowPolicy;
import src.backend.request.dto.DecideChangeRequestRequest;
import src.backend.request.dto.DecideChangeRequestResponse;
import src.backend.request.entity.ChangeRequest;
import src.backend.request.entity.ChangeRequestType;
import src.backend.request.event.ChangeRequestDecidedEvent;
import src.backend.request.preview.spec.ApprovalPreview;
import src.backend.request.preview.spec.ApprovalPreviewCache;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.routing.engine.spec.OrderedStop;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.RouteVersion;
import src.backend.routing.entity.RouteVersionSource;
import src.backend.routing.entity.RunStop;
import src.backend.routing.pipeline.RouteComputation;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.entity.Run;
import src.backend.run.event.RunRouteConfirmedEvent;
import src.backend.run.repository.RunRepository;

/**
 * ②구간 승인 결정(API_SPEC §5.6 {@code POST /staff/approvals/{id}/decide}) — 승인·거절 두 경로를
 * 한 트랜잭션 안에서 처리한다.
 *
 * <p><b>확정 배치({@code RunConfirmationPersistence})와 달리 계산·저장을 분리하지 않는다</b> —
 * 그쪽이 분리한 이유는 외부 지도 API 호출을 트랜잭션 밖에 두기 위해서인데, 이 결정 경로는 승인 상세
 * 조회 시점({@code ApprovalQueryService.detail})에 이미 계산해 캐시({@link ApprovalPreviewCache})에
 * 넣어 둔 {@link RouteComputation} 을 <b>그대로 재사용</b>할 뿐 새로 계산하지 않는다(T4 의 온디맨드
 * 캐싱 설계). 그래서 이 메서드 전체를 하나의 {@code @Transactional} 로 묶어도 커넥션을 오래 붙들
 * 외부 호출이 없고, 그 대신 목표 7(실패 시 원자적 롤백)을 구조적으로 보장한다.
 *
 * <p><b>{@code preview_token} 검사가 이 클래스에서 가장 위험한 자리다</b> — 캐시에 없거나 토큰이
 * 다르면(완전히 빠진 요청 포함, {@code null} 은 어떤 캐시 토큰과도 같을 수 없다) {@code 409
 * PREVIEW_STALE} 로 막는다. 이 검사를 생략하면 화면에서 본 것과 다른 노선이 배포돼도 상태 코드·버전
 * 숫자만 보는 검증으로는 걸리지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ChangeRequestDecisionService {

    private final ChangeRequestRepository changeRequestRepository;

    private final RunRepository runRepository;

    private final RunRiderRepository runRiderRepository;

    private final ApprovalPreviewCache previewCache;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RouteVersionRepository routeVersionRepository;

    private final RunStopRepository runStopRepository;

    private final ApplicationEventPublisher eventPublisher;

    private final Clock clock;

    /**
     * @throws BusinessException {@code 404 APPROVAL_NOT_FOUND} · {@code 403 ACADEMY_SCOPE_VIOLATION} ·
     *                            {@code 409 APPROVAL_ALREADY_DECIDED}(이미 처리된 건, 다른 검사보다
     *                            먼저 본다) · {@code 403 CHANGE_WINDOW_CLOSED}(운행 시작 후 도달,
     *                            Ruling 200) · {@code 409 PREVIEW_STALE}(승인, 토큰 불일치·부재) ·
     *                            {@code 422 VALIDATION_FAILED}(거절, 사유 부재)
     */
    @Transactional
    public DecideChangeRequestResponse decide(AuthUser requester, Long approvalId, DecideChangeRequestRequest req) {
        ChangeRequest cr = changeRequestRepository.findById(approvalId)
                .orElseThrow(() -> new BusinessException(ErrorCode.APPROVAL_NOT_FOUND));
        AcademyScope.assertAccessible(requester, cr.getAcademyId());
        // 이미 처리된 건은 창 판정보다 먼저 걸러야 한다 — 두 번째 decide 시도는 캐시가 이미 비어 있어
        // (§ evict), 창 검사보다 뒤에 두면 PREVIEW_STALE 로 오답한다.
        cr.assertPending();

        Long academyId = cr.getAcademyId();
        Run run = runRepository.findByIdAndAcademyId(cr.getRunId(), academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));

        OffsetDateTime decidedAt = OffsetDateTime.now(clock);
        // T6 의 폴링(30초)만 믿으면 그 사이 도착한 결정이 새어 나간다 — 여기서 직접·동기로 판정한다.
        if (ChangeWindowPolicy.segmentOf(run, decidedAt) == ChangeWindow.CLOSED) {
            throw new BusinessException(ErrorCode.CHANGE_WINDOW_CLOSED);
        }

        if (Boolean.TRUE.equals(req.approve())) {
            return approve(requester, cr, run, decidedAt, req.previewToken());
        }
        return reject(requester, cr, run, decidedAt, req.rejectReason());
    }

    /**
     * 승인 — 명단 제외(취소형)·경유지 이동(이동형) → 캐시된 재최적화 결과를 새 노선 버전으로 배포 →
     * 기사·동승자 재배포({@code route_changed}, Phase 7 경로 재사용) + 학부모 통보.
     */
    private DecideChangeRequestResponse approve(AuthUser requester, ChangeRequest cr, Run run,
            OffsetDateTime decidedAt, String previewToken) {
        Long academyId = cr.getAcademyId();
        ApprovalPreview preview = previewCache.find(cr.getId())
                .filter(cached -> cached.token().equals(previewToken))
                .orElseThrow(() -> new BusinessException(ErrorCode.PREVIEW_STALE));
        RouteComputation computation = preview.computation();

        List<RunRider> riders = runRiderRepository.findAllByRunIdAndAcademyId(run.getId(), academyId);
        RunRider target = riders.stream()
                .filter(rider -> rider.getStudentId().equals(cr.getStudentId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "승인 대상 학생이 회차 명단에 없다 — runId=" + run.getId() + ", studentId=" + cr.getStudentId()));
        Long originalStopId = target.getStopId();

        ConfirmedRoute confirmedRoute = confirmedRouteRepository.findById(run.getId())
                .orElseThrow(() -> new IllegalStateException("확정 노선이 없다 — runId=" + run.getId()));
        RouteVersion currentVersion = routeVersionRepository.findById(confirmedRoute.getCurrentVersionId())
                .orElseThrow(() -> new IllegalStateException(
                        "노선 버전이 없다 — versionId=" + confirmedRoute.getCurrentVersionId()));
        List<RunStop> beforeRunStops = runStopRepository
                .findAllByRouteVersionIdAndAcademyIdOrderBySeq(currentVersion.getId(), academyId);

        // "잔여 0명이면 승하차지 제거" — 대상 학생이 떠나는 원래 승하차지가 재최적화 결과에도 남아
        // 있는지로 판정한다(전/후 대조는 실제 배포될 computation 을 기준으로 한다, 사각지대 회피).
        boolean stopRemoved = beforeRunStops.stream().anyMatch(rs -> originalStopId.equals(rs.getStopId()))
                && computation.stops().stream().noneMatch(os -> originalStopId.equals(os.stopId()));

        int newVersionNo = currentVersion.getVersionNo() + 1;
        RouteVersion newVersion = RouteVersion.forConfirmedRoute(run.getId(), newVersionNo,
                RouteVersionSource.APPROVAL, computation.estDurationMin(), computation.estDistanceKm(), decidedAt,
                preview.fingerprint(), computation.snapshot().engineName(), computation.snapshot().policySnapshot(),
                computation.snapshot().fallbackUsed(), requester.accountId(), decidedAt);
        routeVersionRepository.save(newVersion);
        confirmedRouteRepository.assignCurrentVersion(run.getId(), newVersion.getId());
        runStopRepository.saveAll(runStopsOf(newVersion.getId(), computation));

        // assignCurrentVersion 이 clearAutomatically=true 라 위 호출 시점에 영속 컨텍스트 전체가
        // 비워진다 — target·cr 은 그 이전에 로드해 둔 인스턴스라 이 시점부턴 준영속(detached) 상태다.
        // 세터만 부르고 끝내면 변경이 메모리에만 남고 커밋 때 반영되지 않으므로, 각 저장소의 save()
        // 로 명시적으로 다시 붙인다(merge, ConfirmedRoute.assignCurrentVersion 의 javadoc이 이미 경고한
        // 것과 같은 함정).
        if (cr.getType() == ChangeRequestType.CANCEL) {
            target.markAbsent(decidedAt);
        } else {
            target.relocateTo(cr.getNewStopId(), decidedAt);
        }
        runRiderRepository.save(target);

        cr.approve(requester.accountId(), decidedAt, stopRemoved, newVersion.getId());
        changeRequestRepository.save(cr);
        previewCache.evict(cr.getId());

        // route_changed — Phase 7 이 이미 만든 경로를 재사용한다(새 발행 경로를 만들지 않는다).
        eventPublisher.publishEvent(
                new RunRouteConfirmedEvent(run.getId(), academyId, run.getBusId(), decidedAt));
        eventPublisher.publishEvent(new ChangeRequestDecidedEvent(academyId, cr.getId(), run.getId(),
                cr.getStudentId(), cr.getRequestedBy(), true, null, decidedAt));

        return new DecideChangeRequestResponse("approved", stopRemoved, newVersionNo, requester.accountId(),
                decidedAt);
    }

    /** 거절 — 재최적화를 전혀 부르지 않고 기존 노선을 유지한 채 사유와 함께 학부모에게 통보한다. */
    private DecideChangeRequestResponse reject(AuthUser requester, ChangeRequest cr, Run run,
            OffsetDateTime decidedAt, String rejectReason) {
        if (rejectReason == null || rejectReason.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        cr.reject(requester.accountId(), decidedAt, rejectReason);
        previewCache.evict(cr.getId());

        eventPublisher.publishEvent(new ChangeRequestDecidedEvent(cr.getAcademyId(), cr.getId(), run.getId(),
                cr.getStudentId(), cr.getRequestedBy(), false, rejectReason, decidedAt));

        Integer currentVersionNo = confirmedRouteRepository.findById(run.getId())
                .map(ConfirmedRoute::getCurrentVersionId)
                .flatMap(routeVersionRepository::findById)
                .map(RouteVersion::getVersionNo)
                .orElse(null);

        return new DecideChangeRequestResponse("rejected", false, currentVersionNo, requester.accountId(),
                decidedAt);
    }

    /** {@code computation.stops()} 와 {@code etas()} 는 자리로 대응한다({@code RunConfirmationPersistence} 와 같은 헬퍼). */
    private static List<RunStop> runStopsOf(Long versionId, RouteComputation computation) {
        List<OrderedStop> stops = computation.stops();
        List<OffsetDateTime> etas = computation.etas();
        List<RunStop> runStops = new ArrayList<>(stops.size());
        for (int i = 0; i < stops.size(); i++) {
            OrderedStop stop = stops.get(i);
            OffsetDateTime eta = etas.get(i);
            if (stop.stopId() != null) {
                runStops.add(RunStop.forStop(versionId, stop.stopId(), stop.seq(), eta));
            } else {
                runStops.add(RunStop.forWaypoint(versionId, stop.waypointId(), stop.seq(), eta));
            }
        }
        return runStops;
    }
}
