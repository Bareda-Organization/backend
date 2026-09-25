package src.backend.location.command;

import java.time.Clock;
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

    public void receive(AuthUser requester, Long runId, RunPositionRequest request) {
        runAssignmentAccess.assertAssignedDriver(requester, runId);
        Run run = runRepository.findByIdAndAcademyId(runId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        if (run.getStatus() != RunStatus.MOVING) {
            throw new BusinessException(ErrorCode.RUN_NOT_MOVING);
        }

        OffsetDateTime receivedAt = OffsetDateTime.now(clock);
        RunPosition position = RunPosition.onReceive(runId, request.lat(), request.lng(),
                request.recordedAt(), receivedAt, request.speed(), request.heading());
        runPositionRepository.save(position);

        List<RunStop> ordered = orderedStopsOf(run);
        eventPublisher.publishEvent(new RunPositionReceivedEvent(runId, request.lat(), request.lng(),
                request.recordedAt(), receivedAt, run.getAcademyId(), currentStopNameOf(ordered), nextEtaOf(ordered)));
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

    /** 가장 최근 도착 처리된 정차 항목의 이름 — "도착 시각이 채워진 정차 중 seq 최댓값"(§4.3 과 같은 판정). */
    private String currentStopNameOf(List<RunStop> ordered) {
        return ordered.stream()
                .filter(stop -> stop.getArrivedAt() != null)
                .max(Comparator.comparingInt(RunStop::getSeq))
                .map(this::nameOf)
                .orElse(null);
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
