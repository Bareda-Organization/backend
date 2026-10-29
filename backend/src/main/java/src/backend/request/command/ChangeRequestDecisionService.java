package src.backend.request.command;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.request.domain.ChangeWindow;
import src.backend.request.domain.ChangeWindowPolicy;
import src.backend.request.dto.DecideChangeRequestRequest;
import src.backend.request.dto.DecideChangeRequestResponse;
import src.backend.request.entity.ChangeRequest;
import src.backend.request.preview.spec.ApprovalPreview;
import src.backend.request.preview.spec.ApprovalPreviewCache;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.routing.pipeline.RouteComputation;
import src.backend.run.command.RunRowLock;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;

/**
 * ②구간 승인 결정(API_SPEC §5.6 {@code POST /staff/approvals/{id}/decide}) — 승인·거절 두 경로를
 * 한 트랜잭션 안에서 처리한다.
 *
 * <p><b>확정 배치({@code RunConfirmationPersistence})와 달리 계산·저장을 분리하지 않는다</b> —
 * 그쪽이 분리한 이유는 외부 지도 API 호출을 트랜잭션 밖에 두기 위해서인데, 이 결정 경로는 승인 상세
 * 조회 시점({@code ApprovalQueryService.detail})에 이미 계산해 캐시({@link ApprovalPreviewCache})에
 * 넣어 둔 {@link RouteComputation} 을 <b>그대로 재사용</b>할 뿐 새로 계산하지 않는다(온디맨드
 * 캐싱 설계). 그래서 이 메서드 전체를 하나의 {@code @Transactional} 로 묶어도 커넥션을 오래 붙들
 * 외부 호출이 없고, 그 대신 실패 시 원자적 롤백을 구조적으로 보장한다.
 *
 * <p><b>{@code preview_token} 검사가 이 클래스에서 가장 위험한 자리다</b> — 캐시에 없거나 토큰이
 * 다르면(완전히 빠진 요청 포함, {@code null} 은 어떤 캐시 토큰과도 같을 수 없다) {@code 409
 * PREVIEW_STALE} 로 막는다. 이 검사를 생략하면 화면에서 본 것과 다른 노선이 배포돼도 상태 코드·버전
 * 숫자만 보는 검증으로는 걸리지 않는다.
 *
 * <p><b>토큰 일치만으로는 부족하다</b> — 같은 회차에 대기 중인 다른 승인 건이 먼저 결정되면, 이
 * 요청의 토큰은 캐시에 그대로 남아 있어도(아직 이 건은 처리 전이라 evict 되지 않는다) 그 토큰이
 * 가리키는 계산은 이미 낡은 명단을 전제한 것이다. 그래서 승인 시점에 {@code ApprovalQueryService.detail}
 * 과 <b>같은 재료</b>(현재 명단·기준점)로 지문을 다시 뽑아 {@link ApprovalPreview#fingerprint()} 와
 * 대조하고, 어긋나면 토큰이 일치해도 {@code 409 PREVIEW_STALE} 로 막는다(§ approve 지문 재계산).
 */
@Service
@RequiredArgsConstructor
public class ChangeRequestDecisionService {

    private final ChangeRequestRepository changeRequestRepository;

    private final RunRepository runRepository;

    /** 승인 경로(재최적화 결과 배포 · 명단 반영 · 통보) — 이 서비스의 트랜잭션에 그대로 참여한다. */
    private final ChangeRequestApprovalStep approvalStep;

    /** 거절 경로 — 이 서비스의 트랜잭션에 그대로 참여한다. */
    private final ChangeRequestRejectionStep rejectionStep;

    /** 요청 행({@code change_request})을 잠그기 전에 먼저 쥐는 회차 행 잠금 — 운행 시작(회차 → 요청)과 같은 순서({@link RunRowLock}). */
    private final RunRowLock runRowLock;

    private final Clock clock;

    /**
     * @throws BusinessException {@code 404 APPROVAL_NOT_FOUND}(없음 · 다른 학원, §1.5) ·
     *                            {@code 409 APPROVAL_ALREADY_DECIDED}(이미 처리된 건, 다른 검사보다
     *                            먼저 본다) · {@code 403 CHANGE_WINDOW_CLOSED}(운행 시작 후 도달,
     *                            Ruling 200) · {@code 409 RUN_CANCELED}(승인, 취소된 회차, Ruling 376) · {@code 409 PREVIEW_STALE}(승인, 토큰 불일치·부재) ·
     *                            {@code 422 VALIDATION_FAILED}(거절, 사유 부재)
     */
    @Transactional
    public DecideChangeRequestResponse decide(AuthUser requester, Long approvalId, DecideChangeRequestRequest req) {
        ChangeRequest cr = lockPendingRequest(requester, approvalId);
        Run run = runRepository.findByIdAndAcademyId(cr.getRunId(), cr.getAcademyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));

        OffsetDateTime decidedAt = OffsetDateTime.now(clock);
        assertChangeWindowOpen(run, decidedAt);

        if (Boolean.TRUE.equals(req.approve())) {
            return approvalStep.approve(requester, cr, run, decidedAt, req.previewToken());
        }
        return rejectionStep.reject(requester, cr, run, decidedAt, req.rejectReason());
    }

    /** 회차 행 → 요청 행 순으로 잠그고 아직 처리 전인 요청을 읽는다(없음 · 다른 학원 · 이미 처리됨은 예외). */
    private ChangeRequest lockPendingRequest(AuthUser requester, Long approvalId) {
        // 회차 행을 요청 행보다 먼저 쥔다 — 운행 시작은 회차 행 → 요청 행(자동 거절) 순이라, 요청 행을 먼저 쥐고 승인에서 회차 행을
        // 기다리면 서로를 기다리는 교착(deadlock detected)이 된다. 회차 id 는 잠금 없이 값으로만 읽는다(없거나 다른 학원이면 아래와 같은 404).
        Long runId = changeRequestRepository.findRunIdByIdAndAcademyId(approvalId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.APPROVAL_NOT_FOUND));
        runRowLock.acquire(runId, requester.academyId());
        // 행 잠금으로 읽는다 — 자동 거절과 겹쳐도 커밋된 상태로 판정한다(BR-028).
        ChangeRequest cr = changeRequestRepository.findForDecision(approvalId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.APPROVAL_NOT_FOUND));
        // 이미 처리된 건은 창 판정보다 먼저 걸러야 한다 — 두 번째 decide 시도는 캐시가 이미 비어 있어
        // (§ evict), 창 검사보다 뒤에 두면 PREVIEW_STALE 로 오답한다.
        cr.assertPending();
        return cr;
    }

    /** 변경 창이 닫혔으면 {@code 403 CHANGE_WINDOW_CLOSED} 로 막는다. */
    private void assertChangeWindowOpen(Run run, OffsetDateTime decidedAt) {
        // 자동 거절 스케줄러의 폴링(30초)만 믿으면 그 사이 도착한 결정이 새어 나간다 — 여기서 직접·동기로 판정한다.
        if (ChangeWindowPolicy.segmentOf(run, decidedAt) == ChangeWindow.CLOSED) {
            throw new BusinessException(ErrorCode.CHANGE_WINDOW_CLOSED);
        }
    }

}
