package src.backend.request.command;

import java.time.OffsetDateTime;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.request.dto.DecideChangeRequestResponse;
import src.backend.request.entity.ChangeRequest;
import src.backend.request.event.ChangeRequestDecidedEvent;
import src.backend.request.preview.spec.ApprovalPreviewCache;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.RouteVersion;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.run.entity.Run;

/** ②구간 승인 결정의 거절 경로 — 호출한 결정 트랜잭션에 그대로 참여한다. */
@Component
@RequiredArgsConstructor
class ChangeRequestRejectionStep {

    private final ApprovalPreviewCache previewCache;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RouteVersionRepository routeVersionRepository;

    private final ApplicationEventPublisher eventPublisher;

    /** 거절 — 재최적화를 전혀 부르지 않고 기존 노선을 유지한 채 사유와 함께 학부모에게 통보한다. */
    DecideChangeRequestResponse reject(AuthUser requester, ChangeRequest cr, Run run,
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

}
