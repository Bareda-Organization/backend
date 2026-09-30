package src.backend.location.command;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.location.dto.RunPositionRequest;
import src.backend.location.entity.RunPosition;
import src.backend.location.event.RunPositionReceivedEvent;
import src.backend.location.repository.RunPositionRepository;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.RunStop;
import src.backend.routing.entity.Waypoint;
import src.backend.routing.query.CurrentRunStopResolver;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.routing.repository.WaypointRepository;
import src.backend.run.access.RunAssignmentAccess;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.RunRepository;
import src.backend.student.entity.Stop;
import src.backend.student.repository.StopRepository;

/**
 * 기사 단말의 위치 송신 처리(API_SPEC §4.12, LOC-01, 목표 1·2·3) —
 * {@link src.backend.run.command.RunArrivalCommandService} 와 같은 인가·상태 판정 순서를 따른다
 * (배치 기사인지 → 회차 존재 → {@code moving} 인지).
 *
 * <p><b>{@code run_position} 적재가 먼저, Redis 갱신은 커밋 후</b>(조율자 판단, 목표 3) — 이력 유실은
 * 되돌릴 수 없지만 Redis 만 유실되면 2초(Ruling 279) 뒤 다음 송신이 덮어써 스스로 회복되므로, 회복
 * 가능한 쪽을 나중에 둔다. 이 서비스는 이벤트만 발행하고, 실제 Redis 쓰기는
 * {@link RunPositionRedisListener} 가 {@code AFTER_COMMIT} 에서 한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class RunPositionCommandService {

    private final RunRepository runRepository;

    private final RunPositionRepository runPositionRepository;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RunStopRepository runStopRepository;

    private final StopRepository stopRepository;

    private final WaypointRepository waypointRepository;

    private final RunAssignmentAccess runAssignmentAccess;

    private final ApplicationEventPublisher eventPublisher;

    private final Clock clock;

    /** 단말 {@code recorded_at} 과 서버 수신 시각의 허용 차이(양방향) — 기기 시계 오차를 넉넉히 받되 하루 단위 어긋남은 수신 시각으로 대체한다(BR-243). */
    private static final Duration RECORDED_AT_TOLERANCE = Duration.ofMinutes(5);

    /**
     * 기사 단말의 위치 1건을 적재하고 {@link RunPositionReceivedEvent} 를 발행한다 — 배치 기사인지 ·
     * 회차 존재 · {@code moving} 상태 순으로 거절 조건을 확인한 뒤(API_SPEC §4.12), Redis 갱신은
     * 이벤트 구독자가 커밋 후에 한다.
     */
    public void receive(AuthUser requester, Long runId, RunPositionRequest request) {
        runAssignmentAccess.assertAssignedDriver(requester, runId);
        Run run = runRepository.findByIdAndAcademyId(runId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        if (run.getStatus() != RunStatus.MOVING) {
            throw new BusinessException(ErrorCode.RUN_NOT_MOVING);
        }

        OffsetDateTime receivedAt = OffsetDateTime.now(clock);
        // 기기 시계는 신뢰 경계 밖이다 — 어긋난 recorded_at 을 그대로 두면 Redis 장애 대체 조회의 "최신" 이나 보존 정리 기준이
        // 틀어지고, 거절하면 시계가 틀어진 기사의 위치가 전부 사라진다. 그래서 수신 시각으로 바꿔 저장한다(BR-243, Ruling 379 ②)
        OffsetDateTime recordedAt = isSkewed(request.recordedAt(), receivedAt) ? receivedAt : request.recordedAt();
        RunPosition position = RunPosition.onReceive(runId, request.lat(), request.lng(),
                recordedAt, receivedAt, request.speed(), request.heading());
        runPositionRepository.save(position);

        List<RunStop> ordered = orderedStopsOf(run);
        eventPublisher.publishEvent(new RunPositionReceivedEvent(runId, request.lat(), request.lng(),
                recordedAt, receivedAt, run.getAcademyId(), currentStopNameOf(ordered), nextEtaOf(ordered)));
    }

    private static boolean isSkewed(OffsetDateTime recordedAt, OffsetDateTime receivedAt) {
        return Duration.between(recordedAt, receivedAt).abs().compareTo(RECORDED_AT_TOLERANCE) > 0;
    }

    /** 확정 노선의 정차 순서 — 확정 노선이 없으면 빈 목록. 위치 1건에 한 번만 읽는다(BR-100). */
    private List<RunStop> orderedStopsOf(Run run) {
        Long versionId = confirmedRouteRepository.findById(run.getId())
                .map(ConfirmedRoute::getCurrentVersionId)
                .orElse(null);
        if (versionId == null) {
            return List.of();
        }
        return runStopRepository.findAllByRouteVersionIdAndAcademyIdOrderBySeq(versionId, run.getAcademyId());
    }

    /** 가장 최근 도착 처리된 정차 항목의 이름(§4.3). */
    private String currentStopNameOf(List<RunStop> ordered) {
        return CurrentRunStopResolver.resolve(ordered).map(this::nameOf).orElse(null);
    }

    /**
     * 그 뒤 첫 정차 항목의 {@code run_stop.eta} 저장값(Ruling 232 확정 — 계획값, 재계산 부재). "마지막 도착 뒤"
     * 에서 고른다 — 도착 처리 대상이 아닌 경유 지점이 미도착으로 남아도 지난 것이다(BR-015).
     */
    private OffsetDateTime nextEtaOf(List<RunStop> ordered) {
        int afterSeq = ordered.stream().filter(stop -> stop.getArrivedAt() != null).mapToInt(RunStop::getSeq)
                .max().orElse(-1);
        return ordered.stream()
                .filter(stop -> stop.getSeq() > afterSeq)
                .min(Comparator.comparingInt(RunStop::getSeq))
                .map(RunStop::getEta)
                .orElse(null);
    }

    private String nameOf(RunStop stop) {
        if (stop.getStopId() != null) {
            return stopRepository.findById(stop.getStopId()).map(Stop::getName).orElse(null);
        }
        if (stop.getWaypointId() == null) {
            return null; // 학원 항목(Ruling 327) — 그 도착은 운행 종료라 위치 송신이 이미 멈춘 뒤다
        }
        return waypointRepository.findById(stop.getWaypointId()).map(Waypoint::getLabel).orElse(null);
    }
}
