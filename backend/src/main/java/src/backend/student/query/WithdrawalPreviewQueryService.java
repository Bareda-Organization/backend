package src.backend.student.query;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.global.security.access.AcademyScope;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.RunRepository;
import src.backend.run.roster.ProjectedRoster;
import src.backend.run.roster.ProjectedRosterReader;
import src.backend.student.dto.WithdrawalPreviewResponse;
import src.backend.student.dto.WithdrawalPreviewResponse.RunItem;
import src.backend.student.entity.Stop;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 퇴원 미리보기(STU-04, API_SPEC §5.11 · Ruling 815) — 오늘·내일 미취소·미종료 회차 중 그 학생이 탑승자인 회차를 돌려준다.
 * 아무것도 바꾸지 않는 읽기다.
 *
 * <p>탑승자 판정은 회차 상태가 가른다 — 확정 뒤(confirmed·moving)는 {@code run_rider}(결석 행은 뺀다 — 그 승하차지 인원에
 * 안 세는 학생이다), 확정 전(idle)은 §5.4 예정 명단과 <b>같은 계산</b>({@link ProjectedRosterReader})을 그대로 읽는다. 화면에
 * 보이는 명단과 이 미리보기가 갈리면 관계자가 본 인원과 퇴원 영향이 어긋난다.
 *
 * <p>ponytail: idle 회차마다 예정 명단을 계산한다 — 학원의 하루 회차 수만큼 질의가 붙는다(수십 건 규모). 수백 건을 넘기면
 * 학생의 요일별 주소·강제 추가·이동으로 후보 회차를 먼저 좁힌다.
 */
@Service
@RequiredArgsConstructor
public class WithdrawalPreviewQueryService {

    private final StudentRepository studentRepository;

    private final RunRepository runRepository;

    private final RunRiderRepository runRiderRepository;

    private final BusRepository busRepository;

    private final StopRepository stopRepository;

    private final ProjectedRosterReader projectedRosterReader;

    /** 운행일(오늘·내일)을 서비스 시간대로 얻는다. */
    private final Clock clock;

    /** 학생 1명의 오늘·내일 영향 회차 — 남의 학원 학생·퇴원생·없는 학생은 {@code 404 STUDENT_NOT_FOUND}(상세와 같다). */
    public WithdrawalPreviewResponse preview(AuthUser requester, Long studentId) {
        Long academyId = AcademyScope.resolveListScope(requester, null)
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
        studentRepository.findByIdAndAcademyIdAndDeletedAtIsNull(studentId, academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STUDENT_NOT_FOUND));
        LocalDate today = LocalDate.now(clock);
        return new WithdrawalPreviewResponse(runsOf(academyId, studentId, today),
                runsOf(academyId, studentId, today.plusDays(1)));
    }

    /** 그날의 미취소·미종료 회차 중 이 학생이 타는 회차를 출발 순서대로 고른다. */
    private List<RunItem> runsOf(Long academyId, Long studentId, LocalDate serviceDate) {
        List<Run> runs = runRepository.findAllByAcademyIdAndServiceDateOrderByDepartTimeAsc(academyId, serviceDate)
                .stream()
                .filter(run -> run.getCanceledAt() == null && run.getStatus() != RunStatus.FINISHED)
                .toList();
        Map<Long, Long> stopIdsByRunId = stopIdsOf(academyId, studentId, runs);
        List<Run> riding = runs.stream().filter(run -> stopIdsByRunId.containsKey(run.getId())).toList();
        if (riding.isEmpty()) {
            return List.of();
        }
        Map<Long, String> busNos = busRepository
                .findAllByAcademyIdAndIdIn(academyId, riding.stream().map(Run::getBusId).distinct().toList()).stream()
                .collect(Collectors.toMap(Bus::getId, Bus::getBusNo));
        Map<Long, String> stopNames = stopRepository
                .findAllByAcademyIdAndIdIn(academyId,
                        stopIdsByRunId.values().stream().filter(id -> id != null).distinct().toList())
                .stream().collect(Collectors.toMap(Stop::getId, Stop::getName));
        return riding.stream()
                .map(run -> new RunItem(run.getId(), busNos.get(run.getBusId()), lower(run.getDirection().name()),
                        run.getDepartTime(), lower(run.getStatus().name()),
                        stopNames.get(stopIdsByRunId.get(run.getId()))))
                .toList();
    }

    /** 회차 id → 이 학생의 승하차지 id(승하차지를 모르면 값이 {@code null}) — 이 학생이 타지 않는 회차는 키가 없다. */
    private Map<Long, Long> stopIdsOf(Long academyId, Long studentId, List<Run> runs) {
        Map<Long, Long> stopIdsByRunId = new HashMap<>();
        List<Long> confirmedRunIds = runs.stream().filter(run -> run.getStatus() != RunStatus.IDLE).map(Run::getId)
                .toList();
        if (!confirmedRunIds.isEmpty()) {
            for (RunRider rider : runRiderRepository.findAllByRunIdInAndAcademyId(confirmedRunIds, academyId)) {
                if (rider.getStudentId().equals(studentId) && rider.getStatus() != RiderStatus.ABSENT) {
                    stopIdsByRunId.put(rider.getRunId(), rider.getStopId());
                }
            }
        }
        for (Run run : runs) {
            if (run.getStatus() != RunStatus.IDLE) {
                continue;
            }
            ProjectedRoster planned = projectedRosterReader.read(run);
            if (planned.studentIds().contains(studentId)) {
                stopIdsByRunId.put(run.getId(), planned.studentStops().get(studentId));
            }
        }
        return stopIdsByRunId;
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
