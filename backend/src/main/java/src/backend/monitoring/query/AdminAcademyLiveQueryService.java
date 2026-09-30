package src.backend.monitoring.query;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.academy.repository.AcademyRepository;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.ManagerRole;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.location.dto.RunPositionRedisValue;
import src.backend.location.infrastructure.RunPositionStore;
import src.backend.manager.dto.AssignedManagerContactView;
import src.backend.manager.repository.AssignmentRepository;
import src.backend.monitoring.dto.AdminAcademyLiveResponse;
import src.backend.routing.entity.RunStop;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;
import src.backend.student.entity.Stop;
import src.backend.student.repository.StopRepository;

/**
 * 메인 관리자 콘솔의 학원 1곳 실시간 관제 조회(API_SPEC §6.8, O-05, 목표 8·9).
 *
 * <p>{@code eta} 계열 값은 전부 {@code run_stop.eta} 저장값을 읽기만 한다 — 좌표·거리로 다시
 * 계산하지 않는다(Ruling 232 확정 — 계획값, 재계산 부재). 좌표는 T1 이 만든 공용
 * {@link RunLiveStateResolver}(§5.18·§6.8 공유, Phase 13 §2)를 그대로 쓴다 — {@code position} 은
 * API_SPEC §6.8 에 선택 필드(`○`)라 Redis 값이 없거나 유실(2분 초과)이면 객체 자체를 {@code null} 로
 * 비운다(필드는 있고 값만 비는 형태로 두지 않는다). 유실이면 {@code last_seen_at} 만 채운다(Ruling
 * 250 · FEATURE_SPEC §4.16 A-14).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminAcademyLiveQueryService {

    private final AcademyRepository academyRepository;

    private final RunRepository runRepository;

    private final BusRepository busRepository;

    private final RunOrderedStopsLoader runOrderedStopsLoader;

    private final StopRepository stopRepository;

    private final AssignmentRepository assignmentRepository;

    private final RunLiveStateResolver runLiveStateResolver;

    private final RunPositionStore runPositionStore;

    private final Clock clock;

    /**
     * 그 학원의 <b>오늘 회차 전부</b>를 상태와 무관하게 돌려준다(R16 목표 1·2, Ruling 315) —
     * {@code idle}·{@code confirmed}·{@code moving}·{@code finished} 4종이 모두 담긴다. 관제 화면이
     * 버스별 상태 목록을 그리려면 {@code moving} 만으로는 성립하지 않고, <b>운행이 끝난 차량도 목록에
     * 남아야 한다</b>(Ruling 310 사용자 확정).
     *
     * <p>⚠ <b>날짜로 좁히는 것이 이 조회의 필수 조건이다.</b> R15 까지는
     * {@code findAllByAcademyIdAndStatus...} 로 <b>상태만</b> 걸러 날짜 조건이 아예 없었다 —
     * {@code moving} 이 사실상 오늘 것뿐이라 드러나지 않았을 뿐이고, 상태 조건을 빼는 순간 과거
     * 회차 전부가 딸려 온다. {@code §5.18}({@code StaffRunLiveQueryService})이 쓰는 것과 같은 조회
     * 메서드를 그대로 재사용한다.
     *
     * <p>운행 전 회차는 관제용 필드가 비어서 나간다 — 노선 확정 전이라 {@code stops} 가 빈 배열
     * ({@link RunOrderedStopsLoader} 가 확정 노선 부재 시 {@code List.of()}), 시작 전이라
     * {@code est_depart_time}({@code run.startedAt})과 {@code position} 이 {@code null} 이다.
     * <b>키는 존재하고 값만 빈다.</b>
     *
     * <p>CODE_CONVENTIONS §20.2 크기 신호 — 나누지 않는다. 오늘 회차·버스·매니저 연락처·정차지를 각각 한 번에 읽어
     * ID 로 묶는 배치 로딩(BR-065, N+1 방지)이 순서대로 이어지는 서술이라, 단계를 private 메서드로
     * 쪼개면 맵 4개를 그만큼 파라미터로 주고받아야 해 오히려 시그니처가 늘어난다.
     */
    public AdminAcademyLiveResponse live(Long academyId) {
        if (!academyRepository.existsById(academyId)) {
            throw new BusinessException(ErrorCode.ACADEMY_NOT_FOUND);
        }

        // 임시 취소된 회차는 뺀다(Ruling 375) — 취소 표시 필드 없이 idle·confirmed 로 섞이지 않게.
        List<Run> todayRuns = runRepository.findAllByAcademyIdAndServiceDateOrderByDepartTimeAsc(academyId,
                LocalDate.now(clock)).stream().filter(run -> !run.isCanceled()).toList();
        if (todayRuns.isEmpty()) {
            return new AdminAcademyLiveResponse(List.of());
        }

        Map<Long, String> busNoByBusId = busRepository
                .findAllByAcademyIdAndIdIn(academyId, todayRuns.stream().map(Run::getBusId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Bus::getId, Bus::getBusNo));

        List<Long> runIds = todayRuns.stream().map(Run::getId).toList();
        Map<Long, List<AssignedManagerContactView>> contactsByRunId = assignmentRepository
                .findAssignedManagerContacts(academyId, runIds).stream()
                .collect(Collectors.groupingBy(AssignedManagerContactView::runId));

        // 확정 노선·정차 순서도 회차 수와 무관한 2쿼리로 읽는다(BR-247)
        Map<Long, List<RunStop>> orderedStopsByRunId = runOrderedStopsLoader.load(academyId, todayRuns);
        // 정차지 이름·좌표는 오늘 회차 전부의 것을 한 번에 읽는다(BR-065) — 정차마다 읽으면 호출 한 번이 수백 쿼리다.
        Map<Long, Stop> stopsById = stopRepository.findAllByAcademyIdAndIdIn(academyId,
                        orderedStopsByRunId.values().stream().flatMap(List::stream).map(RunStop::getStopId)
                                .filter(Objects::nonNull).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Stop::getId, stop -> stop));
        // 좌표도 한 번에 읽는다 — 회차마다 읽으면 Redis 대기·대체 조회가 회차 수만큼 곱해진다(BR-166·BR-167).
        Map<Long, RunPositionRedisValue> positions = runPositionStore.findAll(runIds);

        List<AdminAcademyLiveResponse.Run> runs = todayRuns.stream()
                .map(run -> toRun(run, orderedStopsByRunId.get(run.getId()), stopsById,
                        busNoByBusId.get(run.getBusId()), contactsByRunId.getOrDefault(run.getId(), List.of()),
                        positions.get(run.getId())))
                .toList();
        return new AdminAcademyLiveResponse(runs);
    }

    private AdminAcademyLiveResponse.Run toRun(Run run, List<RunStop> ordered, Map<Long, Stop> stopsById, String busNo,
            List<AssignedManagerContactView> contacts, RunPositionRedisValue latest) {
        List<AdminAcademyLiveResponse.Stop> stops = ordered.stream()
                .filter(stop -> stop.getStopId() != null)
                .map(runStop -> toStop(runStop, stopsById.get(runStop.getStopId())))
                .toList();

        RunLiveState liveState = runLiveStateResolver.resolve(ordered, latest);
        // 유실(2분 초과, Ruling 250 · FEATURE_SPEC §4.16 A-14) 이면 position 을 비우고 last_seen_at 만
        // 채운다 — receivedAt() == null 만 보면(옛 판) 2분 넘게 갱신이 없는데도 마지막 좌표를 계속
        // 내보내는 결함이 된다. §5.18(StaffRunLiveQueryService) 과 같은 판단 기준.
        AdminAcademyLiveResponse.Position position = liveState.receivedAt() != null && !liveState.stale()
                ? new AdminAcademyLiveResponse.Position(liveState.lat(), liveState.lng(), liveState.receivedAt())
                : null;
        OffsetDateTime lastSeenAt = position == null ? liveState.receivedAt() : null;

        return new AdminAcademyLiveResponse.Run(run.getId(), busNo, lower(run.getDirection().name()),
                lower(run.getStatus().name()), position, lastSeenAt, run.getDepartTime(), run.getConfirmAt(), run.getStartedAt(), stops,
                destinationEtaOf(run), contactOf(contacts, ManagerRole.DRIVER), contactOf(contacts,
                        ManagerRole.ESCORT), run.getConsecutiveFailures());
    }

    /**
     * 목적지 도착 예정(Ruling 232 확정 — 계획값, 재계산 부재) = {@code depart_time + est_duration_min}.
     * {@code est_duration_min} 이 아직 없으면(노선 계산 전) {@code null} 이다 — moving 상태는 노선
     * 확정 이후에만 도달하므로 실제로는 거의 항상 채워져 있다(edge case, 보고서 §2 참고).
     */
    private OffsetDateTime destinationEtaOf(Run run) {
        if (run.getEstDurationMin() == null) {
            return null;
        }
        return run.getDepartTime().plusMinutes(run.getEstDurationMin());
    }

    private AdminAcademyLiveResponse.Contact contactOf(List<AssignedManagerContactView> contacts, ManagerRole role) {
        return contacts.stream()
                .filter(contact -> contact.role() == role)
                .findFirst()
                .map(contact -> new AdminAcademyLiveResponse.Contact(contact.name(), contact.phone()))
                .orElse(null);
    }

    /**
     * {@code eta} 는 {@code run_stop.eta} 저장값 그대로다(Ruling 232 확정). 도착 처리
     * ({@code arrived_at != null})면 이미 지난 예정이라 응답에서 비운다 — 저장값 자체를 지우는 것이
     * 아니라 이 조회가 읽을 때만 비운다.
     */
    private AdminAcademyLiveResponse.Stop toStop(RunStop runStop, Stop stop) {
        OffsetDateTime eta = runStop.getArrivedAt() != null ? null : runStop.getEta();
        return new AdminAcademyLiveResponse.Stop(runStop.getStopId(), runStop.getSeq(),
                stop == null ? null : stop.getName(), stop == null ? null : stop.getLat(),
                stop == null ? null : stop.getLng(), runStop.getChange() == null ? null : lower(runStop.getChange()
                        .name()), runStop.getArrivedAt(), eta);
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
