package src.backend.boarding.query;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.audit.service.AuditRecorder;
import src.backend.boarding.dto.ManagerRosterResponse;
import src.backend.boarding.dto.ManagerRosterResponse.Counts;
import src.backend.boarding.dto.ManagerRosterResponse.NoShowCountdown;
import src.backend.boarding.dto.ManagerRosterResponse.RosterStudent;
import src.backend.boarding.dto.ManagerRosterResponse.StopGroup;
import src.backend.boarding.dto.StaffRosterItemResponse;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.exception.entity.NoShowCase;
import src.backend.exception.repository.NoShowCaseRepository;
import src.backend.global.common.enums.ChangeType;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.global.security.access.AcademyScope;
import src.backend.manager.access.ManagerRunAccess;
import src.backend.routing.entity.ConfirmedRoute;
import src.backend.routing.entity.RunStop;
import src.backend.routing.repository.ConfirmedRouteRepository;
import src.backend.routing.repository.RunStopRepository;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.RunRepository;
import src.backend.run.entity.RunTransfer;
import src.backend.run.roster.ProjectedRoster;
import src.backend.run.roster.ProjectedRosterReader;
import src.backend.student.entity.Stop;
import src.backend.student.entity.Student;
import src.backend.student.repository.GuardianPhone;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 회차 명단 조회 — 매니저 앱(§4.2)과 관계자 웹(§5.4)이 <b>같은 원본</b>({@code run_rider} ·
 * {@code run_stop} · {@code Student})을 서로 다른 응답 모양으로 읽는다(RST-01~04·A-04,
 * Phase 9 목표 6·15).
 *
 * <p>두 메서드가 대칭으로 갈리는 지점이 <b>둘</b>이다 — {@code guardian_phone}
 * 마스킹 여부({@link GuardianPhoneMasker})와 {@code absent} 학생의 노출 여부(매니저 앱은 행 제외
 * · 관계자 웹은 빨강 표시로 존치). 한쪽만 고치면 어느 화면이 깨졌는지 코드만 보고는 알 수 없어
 * 이 클래스 하나에 함께 둔다.
 *
 * <p>클래스에 {@code @Transactional} 을 두지 않는다 — 감사 기록({@code AuditRecorder}, {@code REQUIRES_NEW})이 읽기
 * 트랜잭션 안에서 돌면 요청 하나가 DB 연결을 둘 쥐고, 동시 요청이 풀 크기에 닿으면 서로의 두 번째 연결을 기다려
 * 풀리지 않는다(R46 감사 #1). 저장소 호출마다 짧은 읽기 트랜잭션이 돌고, 감사는 조회가 끝난 뒤에 기록한다 —
 * 조회가 예외로 끝나면 감사 호출에 닿지 않는다.
 */
@Service
@RequiredArgsConstructor
public class RosterQueryService {

    private final ManagerRunAccess managerRunAccess;

    private final RunRepository runRepository;

    private final BusRepository busRepository;

    private final ConfirmedRouteRepository confirmedRouteRepository;

    private final RunStopRepository runStopRepository;

    private final RunRiderRepository runRiderRepository;

    private final StopRepository stopRepository;

    private final AcademyRepository academyRepository;

    private final StudentRepository studentRepository;

    private final GuardianStudentRepository guardianStudentRepository;

    private final AuditRecorder auditRecorder;

    private final NoShowCaseRepository noShowCaseRepository;

    private final ProjectedRosterReader projectedRosterReader;

    /**
     * 매니저 앱의 승하차지별 명단(§4.2) — 확정 전(idle) 회차는 {@code 409 RUN_NOT_CONFIRMED}(명단이
     * 아직 채워지지 않아 빈 배열과 "확정됐는데 비었다" 가 구별되지 않기 때문, {@code ErrorCode} 참고).
     *
     * <p>회차 접근 판정은 {@link ManagerRunAccess#requireAssignedRun} 이 먼저 한다(회차 없음·타 학원·
     * 미배치 세 경우 모두 {@code 403 FORBIDDEN} — §1.11, Ruling 259(b)) — 이 상태 검사는 그 다음이다.
     * 접근이 아니라 자원 상태를 묻는 질문이라 별도 계층에 둔다.
     */
    public ManagerRosterResponse managerRoster(AuthUser requester, Long runId) {
        ManagerRunAccess.RunAssignment assigned = managerRunAccess.requireAssignedRun(requester, runId);
        Run run = assigned.run();
        if (run.getStatus() == RunStatus.IDLE) {
            throw new BusinessException(ErrorCode.RUN_NOT_CONFIRMED);
        }
        String busNo = busNoOf(requester, run);
        List<RunRider> riders = runRiderRepository.findAllByRunIdAndAcademyId(run.getId(), requester.academyId());
        Map<Long, Student> studentsById = studentsOf(requester.academyId(), riders);
        Map<Long, String> maskedPhonesById = maskedPhonesOf(requester.academyId(), studentsById.keySet());
        List<RunStop> boardingStops = boardingStopsOf(requester, run);
        Map<Long, Stop> stopsById = stopRepository
                .findAllByAcademyIdAndIdIn(requester.academyId(),
                        boardingStops.stream().map(RunStop::getStopId).filter(id -> id != null).toList())
                .stream()
                .collect(Collectors.toMap(Stop::getId, stop -> stop));
        Academy academy = academyRepository.findById(run.getAcademyId()).orElse(null);
        Map<Long, List<RunRider>> ridersByStopId = riders.stream().collect(Collectors.groupingBy(RunRider::getStopId));
        Map<Long, NoShowCountdown> countdownsByRiderId = noShowCaseRepository
                .findOpenByRunIdAndAcademyId(run.getId(), requester.academyId()).stream()
                .collect(Collectors.toMap(NoShowCase::getRunRiderId,
                        noShowCase -> new NoShowCountdown(String.valueOf(noShowCase.getId()), noShowCase.getStartedAt(),
                                noShowCase.getExpiresAt())));
        List<StopGroup> stops = boardingStops.stream()
                .map(runStop -> runStop.isDestination()
                        ? destinationGroupOf(runStop, academy)
                        : toStopGroup(runStop, stopsById.get(runStop.getStopId()),
                                ridersByStopId.getOrDefault(runStop.getStopId(), List.of()), studentsById,
                                maskedPhonesById, countdownsByRiderId))
                .toList();
        recordManagerRosterAudit(requester, run, stops);
        return new ManagerRosterResponse(String.valueOf(run.getId()), busNo, lower(run.getDirection().name()),
                countsOf(riders), stops);
    }

    /**
     * 매니저 앱 명단 조회 감사(SYS-01 · FEATURE_SPEC §6.3 · Phase 14 T1 목표 1) — {@code photo_url}·
     * {@code note}·{@code address}(정차지, 학생 승하차지 원문 위치)가 L3 다. {@code guardian_phone} 은
     * 이 응답에서 <b>마스킹된 값</b>이라 감사 대상에서 뺀다(§6.3 L2 마스킹 — "원본" 공개가 아니면
     * L3 의 감사 요건이 붙지 않는다).
     *
     * <p>{@code absent} 학생은 이미 응답({@code stops}) 조립 단계에서 행이 빠졌으므로, 그 학생의
     * L3 필드는 애초에 응답에 실리지 않는다 — {@code student_ids} 를 원본 {@code riders} 가 아니라
     * 조립된 {@code stops} 에서 다시 뽑는 이유다. 응답에 학생이 하나도 없으면(전원 결석·미배정)
     * L3 값이 실제로 실리지 않았으므로 행을 남기지 않는다({@code StudentQueryService.detail} 과
     * 같은 근거).
     */
    private void recordManagerRosterAudit(AuthUser requester, Run run, List<StopGroup> stops) {
        List<String> studentIds = stops.stream()
                .flatMap(stopGroup -> stopGroup.students().stream())
                .map(RosterStudent::studentId)
                .distinct()
                .map(String::valueOf)
                .toList();
        if (studentIds.isEmpty()) {
            return;
        }
        auditRecorder.recordDataAccessRead(requester.academyId(), requester.accountId(), "run_roster", run.getId(),
                Map.of("student_ids", studentIds, "fields", List.of("photo_url", "note", "address")));
    }

    /**
     * 관계자 웹의 호차별 일일 명단(§5.4) — 매니저 앱과 달리 확정 전(idle) 회차도 조회할 수 있고
     * (사양 원문 "진입 차단은 매니저 앱 전용"), 배치되지 않았다는 이유로 막지 않는다 — 학원 관계자는
     * 그 학원의 회차 전체를 볼 권한을 이미 {@code STUDENT_READ_SENSITIVE} 로 가졌다.
     *
     * <p>존재 판정과 학원 범위 판정을 분리한다(API_SPEC §1.5, Ruling 239, 2026-09-03 사용자 판정 ②) —
     * 회차 자체가 없으면 {@code 404 RUN_NOT_FOUND}, 있는데 타 학원 소속이면
     * {@link AcademyScope#assertAccessible} 이 {@code 403 ACADEMY_SCOPE_VIOLATION} 을 던진다. 조회
     * 조건에 학원 id 를 섞으면(옛 {@code findByIdAndAcademyId}) 두 사유가 같은 404 로 뭉개진다.
     */
    public List<StaffRosterItemResponse> staffRoster(AuthUser requester, Long runId) {
        Run run = runRepository.findById(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        AcademyScope.assertAccessible(requester, run.getAcademyId());
        List<StaffRosterItemResponse> items = run.getStatus() == RunStatus.IDLE
                ? plannedStaffItems(requester, run)
                : confirmedStaffItems(requester, run);
        recordStaffRosterAudit(requester, run, items);
        return items;
    }

    /** 확정 뒤 회차의 명단 — {@code run_rider} 원본 그대로다. */
    private List<StaffRosterItemResponse> confirmedStaffItems(AuthUser requester, Run run) {
        List<RunRider> riders = runRiderRepository.findAllByRunIdAndAcademyId(run.getId(), run.getAcademyId());
        Map<Long, Student> studentsById = studentsOf(run.getAcademyId(), riders);
        Map<Long, String> rawPhonesById = rawPhonesOf(run.getAcademyId(), studentsById.keySet());
        List<Long> stopIds = riders.stream().map(RunRider::getStopId).distinct().toList();
        Map<Long, Stop> stopsById = stopRepository.findAllByAcademyIdAndIdIn(run.getAcademyId(), stopIds).stream()
                .collect(Collectors.toMap(Stop::getId, stop -> stop));
        Map<Long, RunStop> runStopsByStopId = runStopsByStopId(run);
        return riders.stream()
                .map(rider -> toStaffItem(rider, studentsById.get(rider.getStudentId()), stopsById.get(rider.getStopId()),
                        runStopsByStopId.get(rider.getStopId()), rawPhonesById.get(rider.getStudentId())))
                .toList();
    }

    /**
     * 확정 노선의 현재 버전에서 승하차지 id → 정차 항목(run_stop)을 찾는 표 — 관계자 명단 행의 {@code stop_id}·{@code stop_seq}
     * 출처다(§5.4, Ruling 811). 학원은 요청자가 아니라 회차 소속이다(메인 관리자 토큰은 학원 id 가 없다).
     */
    private Map<Long, RunStop> runStopsByStopId(Run run) {
        Long currentVersionId = confirmedRouteRepository.findById(run.getId())
                .map(ConfirmedRoute::getCurrentVersionId)
                .orElse(null);
        if (currentVersionId == null) {
            return Map.of();
        }
        return runStopRepository.findAllByRouteVersionIdAndAcademyIdOrderBySeq(currentVersionId, run.getAcademyId())
                .stream()
                .filter(runStop -> runStop.getStopId() != null)
                .collect(Collectors.toMap(RunStop::getStopId, runStop -> runStop, (first, duplicate) -> first));
    }

    /**
     * 확정 전(idle) 회차의 예정 명단 — {@code run_rider} 는 확정이 채우므로 아직 비어 있다. 확정 배치와
     * §5.8 이동 판정이 쓰는 {@link ProjectedRosterReader} 규칙을 그대로 읽는다(요일별 주소 − 탑승 OFF +
     * 강제 추가 − 출발 이동 + 도착 이동) — 명단 표시용 계산을 따로 두면 화면에 보이는 학생과 이동 판정이
     * 어긋난다. 탑승 OFF·출발 이동으로 빠진 학생은 넣지 않고, 행은 {@code status=waiting} 이다.
     *
     * <p>{@code change} 는 확정이 붙일 값과 같다(Ruling 370) — 확정({@code RunRiderPersistence#ridersOf})이
     * {@code added} 를 붙이는 집합({@link ProjectedRoster#addedStudentIds()}: 강제 추가·도착 이동)이면
     * {@code added}, 아니면 없다. {@code transfer_id} 는 그중 도착 이동 대기 행에만 실린다(§5.8.1 취소용).
     */
    private List<StaffRosterItemResponse> plannedStaffItems(AuthUser requester, Run run) {
        ProjectedRoster planned = projectedRosterReader.read(run);
        Map<Long, Student> studentsById = studentRepository
                .findAllByAcademyIdAndIdIn(run.getAcademyId(), planned.studentIds()).stream()
                .collect(Collectors.toMap(Student::getId, student -> student));
        Map<Long, String> rawPhonesById = rawPhonesOf(run.getAcademyId(), studentsById.keySet());
        Map<Long, Stop> stopsById = stopRepository
                .findAllByAcademyIdAndIdIn(run.getAcademyId(), planned.studentStops().values().stream().distinct().toList())
                .stream().collect(Collectors.toMap(Stop::getId, stop -> stop));
        Map<Long, Long> transferIdsByStudent = planned.incomingTransfers().stream()
                .collect(Collectors.toMap(RunTransfer::getStudentId, RunTransfer::getId, (first, duplicate) -> first));
        return planned.studentIds().stream()
                .filter(studentsById::containsKey)
                .map(studentId -> {
                    Student student = studentsById.get(studentId);
                    Stop stop = stopsById.get(planned.studentStops().get(studentId));
                    return new StaffRosterItemResponse(studentId, student.getName(), student.getClassName(),
                            stop == null ? null : stop.getName(), null, null, rawPhonesById.get(studentId),
                            planned.addedStudentIds().contains(studentId) ? lower(ChangeType.ADDED.name()) : null,
                            lower(RiderStatus.WAITING.name()), student.getNote(), transferIdsByStudent.get(studentId));
                })
                .toList();
    }

    /**
     * 관계자 웹 명단 조회 감사(SYS-01 · FEATURE_SPEC §6.3 · Phase 14 T1 목표 1) — {@code guardian_phone}
     * 이 이 응답에서는 <b>원본</b>이다(§5.4 마스킹 대상 밖, {@link StaffRosterItemResponse} 자바독) —
     * §6.3 L3 행이 명명한 "보호자 연락처 원본" 이 바로 이 값이라 감사 대상이다. {@code note} 도 L3.
     * {@code photo_url} 은 이 응답에 부재(§5.4 필드 목록에 없음)라 감사 대상에 넣지 않는다.
     *
     * <p>매니저 앱과 달리 {@code absent} 학생도 행으로 남으므로(자바독의 "행 제외 대상 아님") 원본
     * {@code riders} 를 다시 훑지 않고 <b>조립된 응답</b>에서 바로 뽑아도 결과가 같다 — 그래도
     * "응답에 실제로 실린 값" 원칙을 지키려 응답 리스트를 그대로 쓴다.
     */
    private void recordStaffRosterAudit(AuthUser requester, Run run, List<StaffRosterItemResponse> items) {
        List<String> studentIds = items.stream()
                .map(StaffRosterItemResponse::studentId)
                .distinct()
                .map(String::valueOf)
                .toList();
        if (studentIds.isEmpty()) {
            return;
        }
        auditRecorder.recordDataAccessRead(run.getAcademyId(), requester.accountId(), "run_roster", run.getId(),
                Map.of("student_ids", studentIds, "fields", List.of("guardian_phone", "note")));
    }

    private StopGroup toStopGroup(RunStop runStop, Stop stopInfo, List<RunRider> ridersAtStop,
            Map<Long, Student> studentsById, Map<Long, String> maskedPhonesById,
            Map<Long, NoShowCountdown> countdownsByRiderId) {
        List<RosterStudent> students = ridersAtStop.stream()
                // absent 는 개인 행을 빼되, 버스 간 이동으로 빠진 removed 행은 빨강으로 보인다(RTE-04 · §4.2)
                .filter(rider -> rider.getStatus() != RiderStatus.ABSENT || rider.getChange() == ChangeType.REMOVED)
                .map(rider -> toRosterStudent(rider, studentsById.get(rider.getStudentId()),
                        maskedPhonesById.get(rider.getStudentId()), countdownsByRiderId.get(rider.getId())))
                .toList();
        String name = stopInfo == null ? null : stopInfo.getName();
        String address = stopInfo == null ? null : stopInfo.getAddress();
        return new StopGroup(String.valueOf(runStop.getId()), runStop.getSeq(), name, address,
                runStop.getChange() == null ? null : lower(runStop.getChange().name()), runStop.getSkipNotice(),
                runStop.getArrivedAt(), false, students);
    }

    /** 등원 회차의 학원 항목(Ruling 327) — 탈 학생이 없고 이름·주소는 학원 것이다. */
    private StopGroup destinationGroupOf(RunStop runStop, Academy academy) {
        return new StopGroup(String.valueOf(runStop.getId()), runStop.getSeq(),
                academy == null ? null : academy.getName(), academy == null ? null : academy.getAddress(), null, null,
                runStop.getArrivedAt(), true, List.of());
    }

    /** {@code countdown} 은 {@code no_show} 학생에게만 싣는다 — 되돌려 종결된 케이스는 조회에서 이미 빠진다. */
    private RosterStudent toRosterStudent(RunRider rider, Student student, String maskedPhone,
            NoShowCountdown countdown) {
        return new RosterStudent(String.valueOf(rider.getId()), String.valueOf(rider.getStudentId()),
                student.getName(), student.getPhotoUrl(), student.getClassName(), maskedPhone, student.getNote(),
                student.isCanGoAlone(), lower(rider.getStatus().name()),
                rider.getChange() == null ? null : lower(rider.getChange().name()),
                rider.getStatus() == RiderStatus.NO_SHOW ? countdown : null);
    }

    private StaffRosterItemResponse toStaffItem(RunRider rider, Student student, Stop stop, RunStop runStop,
            String rawPhone) {
        return new StaffRosterItemResponse(rider.getStudentId(), student.getName(), student.getClassName(),
                stop == null ? null : stop.getName(), runStop == null ? null : runStop.getId(),
                runStop == null ? null : runStop.getSeq(), rawPhone,
                rider.getChange() == null ? null : lower(rider.getChange().name()), lower(rider.getStatus().name()),
                student.getNote(), null);
    }

    private Counts countsOf(List<RunRider> riders) {
        long boarded = riders.stream().filter(rider -> rider.getStatus() == RiderStatus.BOARDED).count();
        long waiting = riders.stream().filter(rider -> rider.getStatus() == RiderStatus.WAITING).count();
        long noShow = riders.stream().filter(rider -> rider.getStatus() == RiderStatus.NO_SHOW).count();
        // 다른 버스로 옮긴 학생(removed)은 미등원이 아니다
        long absentN = riders.stream()
                .filter(rider -> rider.getStatus() == RiderStatus.ABSENT && rider.getChange() != ChangeType.REMOVED)
                .count();
        return new Counts(boarded, waiting, noShow, absentN);
    }

    /**
     * 확정 노선의 정차 항목 중 <b>학생 승하차지</b>와 등원 <b>학원 항목</b>을 순번대로 고른다 — 강제
     * 경유지(RTE-10)는 도착 처리 대상이 아니라 뺀다. 학원 항목을 빼면 앱에 등원 종료 수단이 없다(Ruling 327).
     */
    private List<RunStop> boardingStopsOf(AuthUser requester, Run run) {
        Long currentVersionId = confirmedRouteRepository.findById(run.getId())
                .map(ConfirmedRoute::getCurrentVersionId)
                .orElse(null);
        if (currentVersionId == null) {
            return List.of();
        }
        List<RunStop> runStops = runStopRepository.findAllByRouteVersionIdAndAcademyIdOrderBySeq(currentVersionId,
                requester.academyId());
        return runStops.stream().filter(stop -> stop.getStopId() != null || stop.isDestination()).toList();
    }

    private Map<Long, Student> studentsOf(Long academyId, List<RunRider> riders) {
        List<Long> studentIds = riders.stream().map(RunRider::getStudentId).distinct().toList();
        return studentRepository.findAllByAcademyIdAndIdIn(academyId, studentIds).stream()
                .collect(Collectors.toMap(Student::getId, student -> student));
    }

    private Map<Long, String> maskedPhonesOf(Long academyId, Set<Long> studentIds) {
        return phonesOf(academyId, studentIds).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> GuardianPhoneMasker.mask(entry.getValue())));
    }

    private Map<Long, String> rawPhonesOf(Long academyId, Set<Long> studentIds) {
        return phonesOf(academyId, studentIds);
    }

    /** 학생 1명에 보호자가 여럿이면 조회가 이미 고정한 정렬의 <b>첫 값</b>만 남긴다(원본과 같은 근거). */
    private Map<Long, String> phonesOf(Long academyId, Set<Long> studentIds) {
        List<GuardianPhone> phones = guardianStudentRepository.findGuardianPhonesByAcademyId(academyId,
                studentIds.stream().toList());
        Map<Long, String> result = new LinkedHashMap<>();
        for (GuardianPhone phone : phones) {
            result.putIfAbsent(phone.getStudentId(), phone.getPhone());
        }
        return result;
    }

    private String busNoOf(AuthUser requester, Run run) {
        return busRepository.findByIdAndAcademyId(run.getBusId(), requester.academyId())
                .map(Bus::getBusNo)
                .orElse(null);
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
