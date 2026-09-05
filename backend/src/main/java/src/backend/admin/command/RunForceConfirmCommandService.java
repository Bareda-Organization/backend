package src.backend.admin.command;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.admin.dto.ForceConfirmResponse;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.RouteVersion;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RouteVersionRepository;
import src.backend.run.command.RunConfirmationService;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.RunRepository;

/**
 * 강제 확정 콘솔 개입(API_SPEC §6.14, F3 S2 목표 11) — idle 로 정체된 회차를 강제 폴백(직선거리)으로
 * 1회 확정한다. "강제 종료"가 아니다 — 미하차 상태로 회차를 끝내는 경로는 이 서비스에 없고, 통상
 * 확정과 같은 결과(route_version 신규 · 관계자 통지 · {@code RunRouteConfirmedEvent})를 그대로 낸다.
 *
 * <p><b>{@link RunConfirmationService} 와 달리 이 서비스는 {@code @Transactional} 이다.</b> 배치
 * 확정 경로가 트랜잭션 밖에 있는 이유는 지도 API 호출처럼 느린 외부 I/O 동안 DB 커넥션을 쥐고 있지
 * 않기 위해서다({@code RunConfirmationService} 클래스 Javadoc). 이 경로는 {@code forceFallback=true}
 * 로 {@link RunConfirmationService#confirmOne(Long, boolean)} 을 호출하므로 실제 지도 API 호출이
 * 애초에 발생하지 않는다 — 그 회피 이유 자체가 성립하지 않아 트랜잭션으로 감싸도 같은 문제가
 * 재도입되지 않는다. 대신 감사 적재를 상태 전이와 같은 트랜잭션에 묶을 수 있다는 이득을 얻는다
 * ({@code AccountUnblockCommandService} 의 "감사 적재가 같은 트랜잭션인 것이 중요하다" 와 같은 근거).
 */
@Service
@RequiredArgsConstructor
public class RunForceConfirmCommandService {

    private final RunRepository runRepository;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RouteVersionRepository routeVersionRepository;

    private final RunConfirmationService runConfirmationService;

    private final Clock clock;

    /**
     * 강제 확정을 실행한다 — 미존재 회차는 {@code 404 RUN_NOT_FOUND}, idle 이 아니면
     * {@code 409 RUN_NOT_IDLE}, {@code confirm_at} 이 아직이면 {@code 409 RUN_NOT_DUE}(§6.14).
     *
     * <p>{@link RunConfirmationService#confirmOne(Long, boolean)} 이 {@code false} 를 반환하는
     * 경우도 {@code RUN_NOT_IDLE} 로 매핑한다 — 이 지점까지 idle 임을 이미 확인했으므로, 그 사이
     * {@code confirmIfIdle} 경쟁에서 진 것만이 남은 원인이다(다른 확정 경로가 먼저 같은 회차를
     * confirmed 로 옮긴 경우).
     *
     * <p>확정 직후 {@link ConfirmedRoute} 를 재조회해 {@code route_version_id}·{@code confirmed_at}
     * 을 얻고, 그 버전의 {@link RouteVersion#isFallbackUsed()} 를 응답에 그대로 싣는다 — 스펙 문면은
     * "항상 {@code true}" 지만, 실제로 저장된 값을 다시 읽는 쪽이 강제 폴백 호출이 조용히 빠지는
     * 결함을 응답 자체가 드러내게 한다(값을 미리 단정해 응답에 박으면 그 결함이 가려진다).
     *
     * @param actorAccountId 강제 확정을 실행하는 메인 관리자 계정 — 감사 행위자
     * @param reason 강제 확정 사유 — 공백뿐인 값은 컨트롤러 진입 전 {@code @NotBlank} 가 이미 거른다
     */
    @Transactional
    public ForceConfirmResponse forceConfirm(Long runId, Long actorAccountId, String reason) {
        Run run = runRepository.findById(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        if (run.getStatus() != RunStatus.IDLE) {
            throw new BusinessException(ErrorCode.RUN_NOT_IDLE);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (run.getConfirmAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.RUN_NOT_DUE);
        }

        boolean persisted = runConfirmationService.confirmOne(runId, true);
        if (!persisted) {
            throw new BusinessException(ErrorCode.RUN_NOT_IDLE);
        }

        ConfirmedRoute confirmedRoute = confirmedRouteRepository.findById(runId)
                .orElseThrow(() -> new IllegalStateException(
                        "confirmOne 이 persisted=true 를 반환했는데 confirmed_route 가 없다: " + runId));
        RouteVersion routeVersion = routeVersionRepository.findById(confirmedRoute.getCurrentVersionId())
                .orElseThrow(() -> new IllegalStateException(
                        "confirmed_route.current_version_id 가 가리키는 route_version 이 없다: "
                                + confirmedRoute.getCurrentVersionId()));

        // TODO(F3 S2 후속): audit_log 적재 — action/category CHECK 제약이 API_SPEC §6.14 의
        // run.force_confirm 문면을 그대로 받을 수 없어 team-lead 판정 대기 중(SendMessage
        // 395e631c-00fd-4bb6-8efb-c5dd951f048a). 판정 도착 즉시 이 자리에 붙인다.

        return new ForceConfirmResponse(runId, confirmedRoute.getCurrentVersionId(), routeVersion.isFallbackUsed(),
                confirmedRoute.getConfirmedAt());
    }
}
