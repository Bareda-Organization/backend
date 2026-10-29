package src.backend.request.query;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.request.PageParams;
import src.backend.global.security.AuthUser;
import src.backend.request.assembly.RoutePreviewAssembler;
import src.backend.request.domain.ChangeWindow;
import src.backend.request.dto.ApprovalCapacityResponse;
import src.backend.request.dto.ApprovalDetailResponse;
import src.backend.request.dto.ApprovalListResponse;
import src.backend.request.dto.ApprovalSummaryResponse;
import src.backend.request.entity.ChangeRequest;
import src.backend.request.entity.ChangeRequestStatus;
import src.backend.request.preview.ApprovalPreviewResolver;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.RunRepository;

/**
 * 승인 대기 목록·상세 조회(API_SPEC §5.5) — 목록은 저장된 값만 집계하고 재최적화를 실행하지 않는다.
 * 상세는 이 시점에 재최적화를 <b>1회</b> 실행해 전/후 대조를 산출한다.
 *
 * <p>⚠ <b>목록·상세를 가른 이유</b>(API_SPEC §5.5) — 재최적화는 계산 비용이 크다. 목록 항목마다
 * 계산하면 대기 건이 N개일 때 한 번의 목록 조회에 N회 최적화가 동기로 실행되어 응답이 지연된다.
 * 승인 화면은 한 건씩 열므로 목록은 요약만, 대조는 상세에서 1건만 계산한다(ARCHITECTURE §8.4).
 *
 * <p>온디맨드 미리보기 계산(캐시 조회·재최적화 1회 실행)은 {@link ApprovalPreviewResolver} 에,
 * 전/후 노선 대조 조립(정차 목록·순번 비교·영향 학생 산출)은 {@link RoutePreviewAssembler} 에 위임한다.
 * 목록 항목 조립은 {@link ApprovalListItemsReader}, 요약 조립은 {@link ApprovalSummaryAssembler},
 * 결정 대기 건의 상세 조립은 {@link ApprovalPendingDetailAssembler}, 결정·배치 인력 조회는
 * {@link ApprovalDecisionReader} 가 맡는다 — 이 클래스는 "무엇을 언제 부르는가" 라는 조회
 * 오케스트레이션만 갖는다(CODE_CONVENTIONS.md §20.2 클래스 크기 관례).
 */
@Service
@RequiredArgsConstructor
public class ApprovalQueryService {

    private final ChangeRequestRepository changeRequestRepository;

    private final RunRepository runRepository;

    private final BusRepository busRepository;

    private final RunRiderRepository runRiderRepository;

    private final ApprovalSummaryAssembler summaryAssembler;

    private final ApprovalListItemsReader listItemsReader;

    private final ApprovalDecisionReader decisionReader;

    private final ApprovalPendingDetailAssembler pendingDetailAssembler;

    /**
     * 승인 대기 목록(§5.5 목록) — 재최적화를 실행하지 않는다. 저장된 값과 단순 집계만 반환한다.
     *
     * <p>DB 에서 페이지 단위로 잘라 온다(§1.8, {@code Ruling 358}) — 회차·버스·학생·승하차지 일괄 조회는
     * 그 페이지에 실린 요청들만 대상으로 한다. 회차·버스·학생·승하차지를 id 목록으로 한 번씩 읽고, 명단은
     * 회차마다 한 번만 읽는다 — 항목마다 따로 읽으면 목록 한 번에 조회가 항목 수에 비례해 는다(BR-075,
     * ARCHITECTURE §14 R3).
     */
    public ApprovalListResponse list(AuthUser requester, ChangeRequestStatus status, PageParams pageParams) {
        Page<ChangeRequest> page = changeRequestRepository.findAllByAcademyIdAndStatusAndWindowSegment(
                requester.academyId(), status, ChangeWindow.APPROVAL_REQUIRED.code(), pageParams.toPageable(sortOf(status)));
        List<ApprovalSummaryResponse> items = listItemsReader.itemsOf(page.getContent(), requester.academyId());
        long pendingCount = changeRequestRepository.countByAcademyIdAndStatus(requester.academyId(), ChangeRequestStatus.PENDING);
        return ApprovalListResponse.of(page, items, pendingCount);
    }

    /**
     * 목록 정렬(§5.5 목록, {@code Ruling 358}) — {@code pending} 은 마감 임박이 위로 오게 오름차순,
     * 결정된 상태는 최근 결정이 위로 오게 내림차순이다. {@code decidedAt} 은 세 전이 메서드
     * ({@link ChangeRequest#approve}·{@link ChangeRequest#reject}·{@link ChangeRequest#autoReject})가
     * 결정 즉시 채우므로 결정된 건에서 {@code null} 일 일이 없지만, 그 전제가 깨지는 경우를 대비해
     * {@code requestedAt} 내림차순을 동순위 결선으로 덧붙인다(정본이 "없으면 requested_at 내림차순"
     * 이라 명시한 자리).
     */
    private Sort sortOf(ChangeRequestStatus status) {
        if (status == ChangeRequestStatus.PENDING) {
            return Sort.by(Sort.Direction.ASC, "deadlineAt");
        }
        return Sort.by(Sort.Direction.DESC, "decidedAt").and(Sort.by(Sort.Direction.DESC, "requestedAt"));
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

        Run run = runRepository.findByIdAndAcademyId(cr.getRunId(), academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        List<RunRider> riders = runRiderRepository.findAllByRunIdAndAcademyId(run.getId(), academyId);
        ApprovalSummaryResponse summary = summaryAssembler.toSummary(cr, run, riders, academyId);
        ApprovalDetailResponse.Decision decision = decisionReader.decisionOf(cr, run.getId(), academyId);

        Bus bus = busRepository.findByIdAndAcademyId(run.getBusId(), academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BUS_NOT_FOUND));
        if (cr.getStatus() != ChangeRequestStatus.PENDING) {
            return decidedDetailOf(summary, run, riders, bus, decision);
        }
        assertRunConfirmed(run);
        return pendingDetailAssembler.pendingDetailOf(approvalId,
                new ApprovalPendingDetailAssembler.Subject(cr, run, riders, bus, summary, decision));
    }

    /** {@code PENDING} 건의 회차가 아직 {@code idle} 이면 {@code 409 RUN_NOT_CONFIRMED} 로 막는다. */
    private void assertRunConfirmed(Run run) {
        // ②구간 승인 대기 신청은 confirmAt 도래 즉시(ChangeWindowPolicy) 성립하지만, 회차의 실제
        // idle → confirmed 전이와 그때 함께 만들어지는 confirmed_route 는 30초 폴링 확정 배치가
        // 돌아야 반영된다(RunConfirmationScheduler). 그 사이 창에서 이 조회가 들어오면 뒤이은
        // ConfirmedRoute 조회가 항상 비어 있어 막지 않으면 원시 IllegalStateException(비구조 500)이
        // 난다 — RosterQueryService.managerRoster 와 같은 경계에서 같은 코드로 막는다.
        if (run.getStatus() == RunStatus.IDLE) {
            throw new BusinessException(ErrorCode.RUN_NOT_CONFIRMED);
        }
    }

    /**
     * 결정이 끝난 건의 상세(§5.5 상세 "왜 결정된 건은 비는가", Ruling 265 로 정본에
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
            Bus bus, ApprovalDetailResponse.Decision decision) {
        long assigned = riders.stream().filter(r -> r.getStatus() != RiderStatus.ABSENT).count();
        ApprovalCapacityResponse capacity = new ApprovalCapacityResponse(bus.getStudentCapacity(), (int) assigned);
        return ApprovalDetailResponse.of(summary, null, run.getDepartTime(), null, null, null, null, null, null,
                List.of(), capacity, null, false, decision);
    }

}
