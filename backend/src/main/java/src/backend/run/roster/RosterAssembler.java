package src.backend.run.roster;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import src.backend.request.entity.ChangeRequest;
import src.backend.run.entity.RunForcedAddition;
import src.backend.run.entity.RunTransfer;
import src.backend.run.roster.ProjectedRoster.StagedRows;
import src.backend.student.projection.StudentDailyStop;

/**
 * 읽어 둔 재료({@link RosterSources})로 한 회차의 예정 명단을 조립한다 — 규칙은 {@link ProjectedRosterReader} 클래스 자바독의 한 곳뿐이다. 조립 한 번마다 새로 만든다.
 */
final class RosterAssembler {

    private final RosterSources sources;

    private final Set<Long> excludedStudentIds;

    /** 강제 추가 병합이 뒤에서 덧붙이므로 가변 목록으로 둔다(RTE-06). */
    private final List<Long> studentIds;

    private final Set<Long> weeklyStudentIds;

    private final Map<Long, Long> studentStops;

    private final Map<Long, Long> absentStops;

    private final Map<Long, Long> stopOverrides;

    private final Map<Long, Long> removedStops = new HashMap<>();

    private RosterAssembler(RosterSources sources) {
        this.sources = sources;
        // ①구간 탑승 의사 토글(riding=false) 학생은 노선 계산에서 빼고 absent 행으로만 남긴다 — absent 는
        // "확정 노선 산출 시점 부여" 이고 "미등원 N명"·관계자 명단(RST-03)이 그 행을 센다(FEATURE_SPEC §3.3).
        this.excludedStudentIds = new HashSet<>(sources.excludedIds());
        this.absentStops = firstStopPerStudent(sources.dailyStops().stream()
                .filter(stop -> excludedStudentIds.contains(stop.getStudentId())));
        this.studentIds = new ArrayList<>(sources.dailyStops().stream().map(StudentDailyStop::getStudentId).distinct()
                .filter(studentId -> !excludedStudentIds.contains(studentId)).toList());
        this.studentStops = firstStopPerStudent(sources.dailyStops().stream()
                .filter(stop -> !excludedStudentIds.contains(stop.getStudentId())));
        this.stopOverrides = approvedRelocations(sources.relocations(), excludedStudentIds);
        this.weeklyStudentIds = Set.copyOf(studentIds);
    }

    /** 재료로 명단을 조립한다. */
    static ProjectedRoster assemble(RosterSources sources) {
        RosterAssembler assembler = new RosterAssembler(sources);
        assembler.addForcedAdditions();
        assembler.applyTransfers();
        return assembler.result();
    }

    private static Map<Long, Long> firstStopPerStudent(Stream<StudentDailyStop> dailyStops) {
        return dailyStops.collect(Collectors.toMap(StudentDailyStop::getStudentId, StudentDailyStop::getStopId,
                (first, duplicate) -> first));
    }

    /**
     * P-06 일일 변경(승인된 경유지 이동)이 요일별 주소를 이긴다 — DailyStopResolver.studentToStop 과
     * 같은 우선순위(그 클래스 자바독). studentStops 에도 같은 값을 덮어써야 fingerprint·run_rider 가
     * 실제로 계산에 쓰인 경유지와 일치한다. 승인된 경유지 이동이 OFF 학생을 명단에 되살리지 않게 한다(BR-012).
     */
    private static Map<Long, Long> approvedRelocations(List<ChangeRequest> relocations, Set<Long> excludedStudentIds) {
        Map<Long, Long> overrides = new HashMap<>(relocations.stream()
                .collect(Collectors.toMap(ChangeRequest::getStudentId, ChangeRequest::getNewStopId,
                        (first, last) -> last)));
        overrides.keySet().removeAll(excludedStudentIds);
        return overrides;
    }

    /**
     * ①구간 강제 추가(RTE-06, Ruling 197·198) — 요일별 주소에 없던 학생이라 studentIds 에도 더하고,
     * stopOverrides 에 넣어야 좌표 해석 단계(DailyStopResolver.studentToStop)가 그 정차지를 찾는다.
     */
    private void addForcedAdditions() {
        for (RunForcedAddition forcedAddition : sources.forcedAdditions()) {
            if (!studentIds.contains(forcedAddition.getStudentId())) {
                studentIds.add(forcedAddition.getStudentId());
            }
            stopOverrides.put(forcedAddition.getStudentId(), forcedAddition.getStopId());
        }
    }

    /**
     * 버스 간 이동(RTE-07, API_SPEC §5.8, Ruling 256) — 출발이면 빼고 도착이면 더한다. 출발 쪽은
     * studentStops·stopOverrides 양쪽에서 지운다 — 하나만 지우면 마지막 putAll 이 되살린다.
     */
    private void applyTransfers() {
        for (RunTransfer transfer : sources.outgoingTransfers()) {
            Long stopId = stopOverrides.getOrDefault(transfer.getStudentId(), studentStops.get(transfer.getStudentId()));
            if (stopId != null) {
                removedStops.put(transfer.getStudentId(), stopId);
            }
            studentIds.remove(transfer.getStudentId());
            studentStops.remove(transfer.getStudentId());
            stopOverrides.remove(transfer.getStudentId());
        }
        for (RunTransfer transfer : sources.incomingTransfers()) {
            if (!studentIds.contains(transfer.getStudentId())) {
                studentIds.add(transfer.getStudentId());
            }
            stopOverrides.put(transfer.getStudentId(), transfer.getStopId());
        }
    }

    private ProjectedRoster result() {
        studentStops.putAll(stopOverrides);
        // 강제 추가·도착 이동으로 다시 태운 학생은 absent 가 아니다 — 한 학생에 행 하나(uk_run_rider_run_student).
        absentStops.keySet().removeAll(studentStops.keySet());
        absentStops.keySet().removeAll(removedStops.keySet());
        Set<Long> addedStudentIds = new HashSet<>(studentIds);
        addedStudentIds.removeAll(weeklyStudentIds);
        return new ProjectedRoster(studentIds, stopOverrides, studentStops, absentStops, addedStudentIds, removedStops,
                sources.incomingTransfers(), stagedRowsOf());
    }

    /**
     * 그 회차의 강제 추가·이동 행 id — 확정 저장이 트랜잭션 안에서 다시 읽은 명단의 id 집합({@link ProjectedRoster#sameInputs})과 다르면 계산 도중 행이 들어오거나
     * 취소된 것이다(BR-044). 이동 대기는 취소로 지워지므로(§5.8.1) 수만 비교하면 취소 1 + 새 등록 1 을 놓친다.
     */
    private StagedRows stagedRowsOf() {
        Set<Long> transferIds = new HashSet<>();
        sources.outgoingTransfers().forEach(transfer -> transferIds.add(transfer.getId()));
        sources.incomingTransfers().forEach(transfer -> transferIds.add(transfer.getId()));
        return new StagedRows(sources.forcedAdditions().stream().map(RunForcedAddition::getId).collect(Collectors.toSet()),
                transferIds);
    }
}
