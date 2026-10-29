package src.backend.request.command;

import java.util.List;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.boarding.entity.RunRider;
import src.backend.global.common.enums.Weekday;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.request.entity.ChangeRequest;
import src.backend.request.preview.ApprovalPreviewResolver;
import src.backend.request.preview.ApprovalPreviewResolver.OriginDestination;
import src.backend.routing.entity.Route;
import src.backend.routing.entity.RouteStop;
import src.backend.routing.pipeline.DailyRoster;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.domain.RunConfirmationFingerprint;
import src.backend.run.entity.Run;

/** ②구간 승인 결정 시점의 미리보기 지문 재검증 — 호출한 결정 트랜잭션에 그대로 참여한다. */
@Component
@RequiredArgsConstructor
class ChangeRequestFingerprintCheck {

    private final AcademyRepository academyRepository;

    private final RouteRepository routeRepository;

    private final RouteStopRepository routeStopRepository;

    private final ApprovalPreviewResolver previewResolver;

    /**
     * 미리보기 지문 재검증 — {@code ApprovalQueryService.detail} 이 토큰을 발급할 때 쓴 것과 <b>같은
     * 재료</b>(현재 명단·기준점)로 지문을 다시 뽑는다. 새 계산 경로를 만들지 않고
     * {@link ApprovalPreviewResolver} 의 조립 메서드를 그대로 재사용한다 — 이 재료가 하나라도
     * 상세 조회 때와 달라지면(가장 흔한 경우가 그 사이 결정된 다른 승인 건이 명단을 바꾼 것) 지문이
     * 어긋나 여기서 잡힌다.
     */
    void assertFingerprintFresh(ChangeRequest cr, Run run, Long academyId, List<RunRider> riders,
            String cachedFingerprint) {
        Weekday weekday = Weekday.of(run.getServiceDate());
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
        String freshFingerprint = RunConfirmationFingerprint.of(academyId, weekday, run.getDirection(),
                run.getDepartTime(), originDestination.origin(), originDestination.destination(),
                roster.stopOverrides(), previewResolver.fixedStopsOf(run, roster));
        if (!freshFingerprint.equals(cachedFingerprint)) {
            throw new BusinessException(ErrorCode.PREVIEW_STALE);
        }
    }

}
