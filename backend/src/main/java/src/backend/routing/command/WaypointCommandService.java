package src.backend.routing.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.request.domain.ChangeWindow;
import src.backend.request.domain.ChangeWindowPolicy;
import src.backend.global.location.GeoPoint;
import src.backend.routing.dto.WaypointRequest;
import src.backend.routing.command.WaypointPreviewCache.WaypointPreview;
import src.backend.routing.dto.WaypointResponse;
import src.backend.routing.engine.spec.FixedStop;
import src.backend.routing.entity.Waypoint;
import src.backend.routing.pipeline.RouteComputationPipeline;
import src.backend.routing.repository.WaypointRepository;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;
import src.backend.student.service.AddressVerification;
import src.backend.student.geocoding.spec.GeocodedPoint;

/**
 * 관계자의 강제 경유 지점 지정·제거 미리보기/배포 오케스트레이터(RTE-10, API_SPEC §5.15).
 *
 * <p><b>{@code @Transactional} 이 없는 것이 이 클래스의 요점이다</b> — {@link RouteComputationPipeline#compute}
 * 가 외부 지도 API 를 부른다(§7 규칙 16, {@code RunConfirmationService}·{@code ApprovalPreviewResolver}
 * 와 같은 근거). "구간 판정 → 좌표 확보 → 재최적화 계산" 은 여기서 트랜잭션 밖에 두고, {@code apply=true}
 * 일 때의 배포 저장만 {@link WaypointStore} 의 짧은 트랜잭션에 맡긴다.
 *
 * <p>"운행 시작 전까지 허용"(§5.15) 은 {@link ChangeWindow#CLOSED} 만 막는 판정이다 — ①②구간
 * (즉시·승인 필요)은 전부 허용하고 ③구간(운행 시작 후)만 막는다는 뜻이라, {@code ChangeRequestCommandService}
 * 와 같은 형태로 판정한다({@code ForcedAdditionCommandService} 처럼 ①구간 전용이 아니다).
 *
 * <p>CODE_CONVENTIONS §20.2 크기 신호(BR-101) — 문맥 조립은 {@link RouteContextAssembler}, 고정 지점 목록 계산은
 * {@link WaypointFixedStops}, 재최적화 미리보기·배포는 {@link WaypointPreviewPublisher}, 응답 조립은
 * {@link WaypointResponseAssembler} 에 맡기고, 여기에는 구간 판정·미리보기 조회·요청 검증과 add·remove 의 흐름만 둔다.
 */
@Service
@RequiredArgsConstructor
public class WaypointCommandService {

    private final RunRepository runRepository;
    private final WaypointRepository waypointRepository;
    private final AddressVerification addressVerification;
    private final WaypointStore waypointStore;
    private final WaypointPreviewCache previewCache;
    private final RouteContextAssembler routeContextAssembler;
    private final WaypointPreviewPublisher previewPublisher;
    private final Clock clock;

    /**
     * 새 경유 지점을 지정한다(§5.15) — {@code apply=false} 는 미리보기, {@code apply=true} 는 그 미리보기의 배포다.
     *
     * <p>미리보기는 항상 행을 먼저 저장해 {@code waypoint_id} 를 응답에 담고, 계산 결과를 {@link WaypointPreviewCache}
     * 에 {@code preview_token} 으로 남긴다. 새 행을 만들기 전에 이 회차의 <b>미배포 행을 전부 지운다</b> — 관계자가
     * 라벨·주소를 바꿔 가며 미리보기를 여러 번 눌러 보는 것이 정상 흐름이라, 그대로 두면 고아 행이 호출마다 쌓인다.
     * 이 "지우고 저장" 두 쓰기는 {@link WaypointStore#saveCandidate} 의 짧은 트랜잭션으로 묶여 있다.
     *
     * <p>배포는 토큰이 가리키는 미리보기의 지점·순번·계산을 그대로 쓴다(BR-051) — 다시 계산하면 관리자가 본
     * 대조표와 다른 노선이 나갈 수 있다(ARCHITECTURE §8.4). 본문의 지점 값은 배포에 쓰이지 않는다.
     */
    public WaypointResponse add(AuthUser requester, Long runId, WaypointRequest request) {
        Run run = loadRunInWindow(requester, runId);
        if (request.apply()) {
            return deployAdd(run, request, requester.accountId());
        }
        GeoPoint point = resolvePoint(request);
        Waypoint waypoint = waypointStore.saveCandidate(run, Waypoint.forRun(run.getId(), request.label(),
                request.address(), point.lat(), point.lng(), request.note(), requester.accountId(),
                OffsetDateTime.now(clock)));
        RouteContext ctx = routeContextAssembler.contextOf(run);
        int seq = seqOf(request, ctx);
        return previewPublisher.preview(new WaypointChange(run, waypoint, ctx,
                WaypointFixedStops.addedFixedStops(ctx, waypoint, seq)), seq, false);
    }

    /**
     * 이미 배포된 경유 지점을 제거한다 — 추가와 같은 미리보기 → 배포 절차다. 미리보기 단계(아직 배포되지 않은)
     * 행은 대상이 아니다({@code WaypointRepository.findAppliedByIdAndRunIdAndAcademyId} 가 그 행을 걸러
     * {@code 404 WAYPOINT_NOT_FOUND} 로 답한다 — 존재 여부를 응답에서 드러내지 않는 관례).
     */
    public WaypointResponse remove(AuthUser requester, Long runId, Long waypointId, boolean apply,
            String previewToken) {
        Run run = loadRunInWindow(requester, runId);
        Waypoint waypoint = waypointRepository
                .findAppliedByIdAndRunIdAndAcademyId(waypointId, run.getId(), run.getAcademyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.WAYPOINT_NOT_FOUND));
        RouteContext ctx = routeContextAssembler.contextOf(run);
        List<FixedStop> fixedStops = WaypointFixedStops.removedFixedStops(ctx, waypoint);
        WaypointChange change = new WaypointChange(run, waypoint, ctx, fixedStops);
        if (!apply) {
            return previewPublisher.preview(change, 0, true);
        }
        WaypointPreview preview = previewOf(run, previewToken, true);
        if (preview.waypointId() != waypoint.getId()) {
            throw new BusinessException(ErrorCode.PREVIEW_STALE);
        }
        return previewPublisher.deploy(change, preview, requester.accountId());
    }

    /** 추가 배포({@code apply=true}) — 미리보기가 가리키는 미배포 후보 행을 찾아 그 순번 그대로 배포한다. */
    private WaypointResponse deployAdd(Run run, WaypointRequest request, Long accountId) {
        WaypointPreview preview = previewOf(run, request.previewToken(), false);
        Waypoint waypoint = waypointRepository.findById(preview.waypointId())
                .filter(w -> !w.isApplied() && w.getRunId().equals(run.getId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.PREVIEW_STALE));
        RouteContext ctx = routeContextAssembler.contextOf(run);
        return previewPublisher.deploy(new WaypointChange(run, waypoint, ctx,
                WaypointFixedStops.addedFixedStops(ctx, waypoint, preview.seq())), preview, accountId);
    }

    /** 이 회차의 지금 유효한 미리보기 — 토큰이 없거나 다르거나 흐름(추가·제거)이 다르면 {@code 409 PREVIEW_STALE}. */
    private WaypointPreview previewOf(Run run, String token, boolean removal) {
        return previewCache.find(run.getId())
                .filter(preview -> preview.token().equals(token) && preview.removal() == removal)
                .orElseThrow(() -> new BusinessException(ErrorCode.PREVIEW_STALE));
    }

    /**
     * 회차를 학원으로 좁혀 읽고 구간을 판정한다 — ③구간(운행 시작 후)만 막는다(클래스 javadoc). 임시 취소된 회차는
     * {@code 409 RUN_CANCELED} 다(BR-210, Ruling 376).
     */
    private Run loadRunInWindow(AuthUser requester, Long runId) {
        Run run = runRepository.findByIdAndAcademyId(runId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        if (run.isCanceled()) {
            throw new BusinessException(ErrorCode.RUN_CANCELED);
        }
        ChangeWindow window = ChangeWindowPolicy.segmentOf(run, OffsetDateTime.now(clock));
        if (window == ChangeWindow.CLOSED) {
            throw new BusinessException(ErrorCode.CHANGE_WINDOW_CLOSED);
        }
        return run;
    }

    /**
     * 설 자리 — 생략하면 맨 뒤.
     *
     * <p>상한이 <b>정차지 수 + 1</b> 인 이유는 "맨 뒤에 붙이기" 가 그 값이기 때문이다. 넘으면
     * {@code 422} 다 — 조용히 맨 뒤로 보내면 관계자가 지정한 자리와 다른 결과가 나온 것을 알 수 없다.
     */
    private int seqOf(WaypointRequest request, RouteContext ctx) {
        // 학원 항목(Ruling 327)은 계산 대상이 아니라 늘 맨 뒤에 따로 붙는다 — 세지 않는다.
        int last = (int) ctx.beforeRunStops().stream().filter(rs -> !rs.isDestination()).count() + 1;
        if (request.seq() == null) {
            return last;
        }
        if (request.seq() > last) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "설 자리는 1부터 %d 사이여야 합니다".formatted(last));
        }
        return request.seq();
    }

    /** 좌표를 우선하고(재검증 호출을 늘리지 않는다), 없으면 주소를 검증한다. 둘 다 없으면 422. */
    private GeoPoint resolvePoint(WaypointRequest request) {
        if (request.lat() != null && request.lng() != null) {
            return new GeoPoint(request.lat(), request.lng());
        }
        if (request.address() == null || request.address().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        GeocodedPoint geocoded = addressVerification.verifySingle(request.address());
        return new GeoPoint(geocoded.lat(), geocoded.lng());
    }
}
