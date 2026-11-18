package src.backend.request.command;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.command.RunRiderPersistence;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.request.domain.ChangeWindow;
import src.backend.request.domain.ChangeWindowPolicy;
import src.backend.request.dto.DecideChangeRequestResponse;
import src.backend.request.entity.BoardingIntent;
import src.backend.request.entity.ChangeRequest;
import src.backend.request.entity.ChangeRequestType;
import src.backend.request.event.AbsentRecordedEvent;
import src.backend.request.event.ChangeRequestDecidedEvent;
import src.backend.request.preview.spec.ApprovalPreview;
import src.backend.request.preview.spec.ApprovalPreviewCache;
import src.backend.request.repository.BoardingIntentRepository;
import src.backend.request.repository.ChangeRequestRepository;
import src.backend.routing.command.RouteVersionDeploymentService;
import src.backend.routing.command.RouteVersionDeploymentService.DeployedVersion;
import src.backend.routing.entity.RouteVersionSource;
import src.backend.run.entity.Run;
import src.backend.run.event.RunRouteConfirmedEvent;
import src.backend.run.repository.RunRepository;

/** ②구간 승인 결정의 승인 경로 — 호출한 결정 트랜잭션에 그대로 참여한다(자체 트랜잭션 경계 없음). */
@Component
@RequiredArgsConstructor
class ChangeRequestApprovalStep {

    private final ChangeRequestRepository changeRequestRepository;

    private final RunRepository runRepository;

    private final RunRiderRepository runRiderRepository;

    private final BoardingIntentRepository boardingIntentRepository;

    /** 명단 반영 진입점(BR-095) — {@code boarding} 소유 {@code run_rider} 에 직접 쓰지 않는다. */
    private final RunRiderPersistence runRiderPersistence;

    private final ApprovalPreviewCache previewCache;

    private final ChangeRequestFingerprintCheck fingerprintCheck;

    /** 새 노선 버전 배포 진입점(BR-094·095) — {@code routing} 소유 테이블에 직접 쓰지 않는다. */
    private final RouteVersionDeploymentService routeVersionDeploymentService;

    private final ApplicationEventPublisher eventPublisher;

    /**
     * 승인 — 명단 제외(취소형)·경유지 이동(이동형) → 캐시된 재최적화 결과를 새 노선 버전으로 배포 →
     * 기사·동승자 재배포({@code route_changed}, 기존 경로 재사용) + 학부모 통보.
     */
    DecideChangeRequestResponse approve(AuthUser requester, ChangeRequest cr, Run run,
            OffsetDateTime decidedAt, String previewToken) {
        Long academyId = cr.getAcademyId();
        ApprovalPreview preview = lockRunAndFindPreview(cr, run, previewToken);
        List<RunRider> riders = runRiderRepository.findAllByRunIdAndAcademyId(run.getId(), academyId);

        // 지문 재검증 — 이 토큰이 발급된 뒤 같은 회차의 다른 승인 건이 먼저 결정돼 명단이 바뀌었으면
        // 토큰 문자열은 여전히 일치해도 그 계산은 이미 낡은 명단을 전제한 것이다(§ 클래스 javadoc).
        fingerprintCheck.assertFingerprintFresh(cr, run, academyId, riders, preview.fingerprint());

        RunRider target = findTarget(riders, cr);
        DeployedVersion deployment = routeVersionDeploymentService.deployNewVersion(run, preview.computation(),
                preview.fingerprint(), RouteVersionSource.APPROVAL, requester.accountId(), decidedAt,
                target.getStopId());

        applyToRiders(cr, target, decidedAt, requester.accountId());
        recordApproval(cr, requester.accountId(), decidedAt, deployment);
        publishApproved(cr, run, decidedAt);

        return new DecideChangeRequestResponse("approved", deployment.stopRemoved(),
                deployment.newVersion().getVersionNo(), requester.accountId(), decidedAt);
    }

    /** 회차 행을 잠그고(취소된 회차 거부) 캐시에서 토큰이 일치하는 미리보기를 꺼낸다 — 없으면 {@code 409 PREVIEW_STALE}. */
    private ApprovalPreview lockRunAndFindPreview(ChangeRequest cr, Run run, String previewToken) {
        // BR-171 — 경유 지점 배포(routing/command/WaypointStore#assertStillDeployable)와 같은 잠금을
        // 재사용해 이 회차를 잠근다. 두 배포가 같은 순간이면 늦게 온 쪽이 이 자리에서 기다렸다가 방금
        // 배포된 노선 버전을 보고 다음 판본 번호를 매겨야 하는데, 잠금이 없으면 둘 다 같은 옛 버전을
        // 기준으로 같은 판본 번호를 써 uk_route_version_confirmed_route_version_no UNIQUE 위반(500)이
        // 난다. 새 잠금 수단을 만들지 않고 그쪽이 쓰는 저장소 메서드를 호출만 한다. decide 가 요청 행보다 먼저 쥔 잠금이라 여기서는 기다리지 않는다.
        Run locked = runRepository.findLockedByIdAndAcademyId(run.getId(), cr.getAcademyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        // Ruling 376 — 취소된 회차에는 노선을 새로 배포하지 않는다(거절은 허용). 잠근 뒤 판정해야 취소와 겹쳐도 안전하다.
        ChangeWindowPolicy.assertNotCanceled(locked);
        return previewCache.find(cr.getId())
                .filter(cached -> cached.token().equals(previewToken))
                .orElseThrow(() -> new BusinessException(ErrorCode.PREVIEW_STALE));
    }

    /** 명단에서 승인 대상 학생의 탑승 행을 찾는다 — 없으면 {@code STUDENT_NOT_IN_RUN}. */
    private RunRider findTarget(List<RunRider> riders, ChangeRequest cr) {
        return riders.stream()
                .filter(rider -> rider.getStudentId().equals(cr.getStudentId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.STUDENT_NOT_IN_RUN));
    }

    /** 배포가 끝난 뒤 승인 내용을 명단에 반영한다 — 취소형은 탑승 의사도 함께 끈다. */
    private void applyToRiders(ChangeRequest cr, RunRider target, OffsetDateTime decidedAt, Long decidedBy) {
        // assignCurrentVersion 이 clearAutomatically=true 라 deployNewVersion 안에서 영속 컨텍스트
        // 전체가 비워진다 — target·cr 은 그 이전에 로드해 둔 인스턴스라 이 시점부턴 준영속(detached)
        // 상태다. 세터만 부르고 끝내면 변경이 메모리에만 남고 커밋 때 반영되지 않으므로,
        // runRiderPersistence.applyApprovalDecision() 이 그 인스턴스를 다시 save() 로 붙인다(merge,
        // ConfirmedRoute.assignCurrentVersion 의 javadoc이 이미 경고한 것과 같은 함정).
        boolean cancel = cr.getType() == ChangeRequestType.CANCEL;
        runRiderPersistence.applyApprovalDecision(target, cancel, cr.getNewStopId(), decidedAt);
        if (cancel) {
            turnRidingOff(cr, decidedAt, decidedBy);
        }
    }

    /** 요청 행에 승인 결과를 기록·저장하고 미리보기 캐시를 비운다. */
    private void recordApproval(ChangeRequest cr, Long decidedBy, OffsetDateTime decidedAt,
            DeployedVersion deployment) {
        cr.approve(decidedBy, decidedAt, deployment.stopRemoved(), deployment.newVersion().getId());
        changeRequestRepository.save(cr);
        previewCache.evict(cr.getId());
    }

    /** 승인 후 이벤트 발행 — 경로 변경 재배포, 학부모 통보, 취소형이면 관계자 absent 통지. */
    private void publishApproved(ChangeRequest cr, Run run, OffsetDateTime decidedAt) {
        Long academyId = cr.getAcademyId();
        // route_changed — 이미 있는 경로를 재사용한다(새 발행 경로를 만들지 않는다).
        eventPublisher.publishEvent(
                new RunRouteConfirmedEvent(run.getId(), academyId, run.getBusId(), decidedAt));
        eventPublisher.publishEvent(new ChangeRequestDecidedEvent(academyId, cr.getId(), run.getId(),
                cr.getStudentId(), cr.getRequestedBy(), true, null, decidedAt));
        if (cr.getType() == ChangeRequestType.CANCEL) {
            // ②구간 취소 승인 → 관계자 absent 통지(§9.7, BR-110)
            eventPublisher.publishEvent(new AbsentRecordedEvent(run.getId(), academyId, cr.getStudentId(), decidedAt));
        }
    }

    /**
     * ②구간 취소 승인의 탑승 의사 반영(BR-203) — {@code run_rider} 만 {@code absent} 로 바꾸면 학부모 회차
     * 목록이 {@code riding=true} + {@code rider_status=absent} 를 함께 돌려준다. 접수 때 만든 행을 다시
     * 읽어 끈다(영속 컨텍스트가 배포 중 비워져 이전 인스턴스는 쓸 수 없다).
     */
    private void turnRidingOff(ChangeRequest cr, OffsetDateTime decidedAt, Long decidedBy) {
        boardingIntentRepository.insertIfAbsent(cr.getAcademyId(), cr.getRunId(), cr.getStudentId(), decidedAt);
        BoardingIntent intent = boardingIntentRepository.findByRunIdAndStudentId(cr.getRunId(), cr.getStudentId())
                .orElseThrow();
        intent.applyRiding(false, ChangeWindow.APPROVAL_REQUIRED, decidedAt, decidedBy);
        boardingIntentRepository.save(intent);
    }

}
