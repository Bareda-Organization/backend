package src.backend.exception.query;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.entity.RunRider;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.exception.dto.StaffReportItemResponse;
import src.backend.exception.dto.StaffReportListResponse;
import src.backend.exception.entity.ExceptionReport;
import src.backend.exception.entity.ExceptionReportType;
import src.backend.exception.repository.ExceptionReportRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.request.ApiValues;
import src.backend.global.request.PageParams;
import src.backend.global.security.AuthUser;
import src.backend.manager.entity.Manager;
import src.backend.manager.repository.ManagerRepository;
import src.backend.run.entity.Run;
import src.backend.run.repository.RunRepository;
import src.backend.student.entity.Student;
import src.backend.student.repository.StudentRepository;

/**
 * 관계자용 예외 보고 조회(API_SPEC §5.20) — 목록·상세 둘 다 학원 범위로 좁힌다.
 *
 * <p>{@code student_name} 은 저장하지 않는다 — {@code type=guardian_absent} 일 때만 {@code run_rider_id}
 * 로 그 시점의 탑승자를 다시 조인해 채운다({@code ApprovalQueryService.toSummary} 와 같은 조회 시점
 * 조립 관례). {@code handled}·{@code handled_at} 은 항상 {@code false}·{@code null} 이다({@link
 * StaffReportItemResponse} javadoc 참고 — 처리 여부 컬럼이 없다).
 *
 * <p>{@code date} 필터는 {@code reported_at} 의 날짜 성분이다 — {@code run.service_date} 가 아니다
 * ({@link ExceptionReportRepository#search} javadoc 근거). 학원 자정 경계는 {@link Clock#getZone()}
 * (Asia/Seoul, {@code ClockConfig})으로 환산한다.
 */
@Service
@RequiredArgsConstructor
public class ExceptionReportQueryService {

    private final ExceptionReportRepository exceptionReportRepository;

    private final RunRepository runRepository;

    private final BusRepository busRepository;

    private final RunRiderRepository runRiderRepository;

    private final StudentRepository studentRepository;

    private final ManagerRepository managerRepository;

    private final Clock clock;

    /**
     * {@code date} 필터가 없을 때 {@link ExceptionReportRepository#search} 에 넘길 극단 경계값 —
     * {@code reportedAt} 이 이 범위를 벗어날 수 없으므로 사실상 무제한이다. null 을 넘기지 않는 이유는
     * 그 메서드의 javadoc 참고(Postgres 파라미터 타입 추론 실패).
     */
    private static final OffsetDateTime UNBOUNDED_FROM = OffsetDateTime.parse("0001-01-01T00:00:00Z");

    private static final OffsetDateTime UNBOUNDED_TO = OffsetDateTime.parse("9999-12-31T23:59:59Z");

    /** 목록(§5.20 목록) — {@code type}·{@code date}·{@code run_id} 전부 선택적, 페이지네이션 없음 — 최근 200건까지만 돌려준다(BR-228). */
    public StaffReportListResponse list(AuthUser requester, String type, String date, Long runId) {
        ExceptionReportType parsedType = ApiValues.reportType(type);
        LocalDate parsedDate = ApiValues.date(date);

        OffsetDateTime from = UNBOUNDED_FROM;
        OffsetDateTime to = UNBOUNDED_TO;
        if (parsedDate != null) {
            ZoneId zone = clock.getZone();
            from = parsedDate.atStartOfDay(zone).toOffsetDateTime();
            to = parsedDate.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        }

        // 페이징이 없어 행 수를 상한으로 자른다(BR-228) — 최근 보고부터 UNPAGED_LIST_MAX 건.
        List<ExceptionReport> reports = exceptionReportRepository.search(requester.academyId(), parsedType, runId,
                from, to, Limit.of(PageParams.UNPAGED_LIST_MAX));
        return StaffReportListResponse.of(toItems(reports, requester.academyId()));
    }

    /**
     * 상세(§5.20 상세).
     *
     * @throws BusinessException {@code 404 REPORT_NOT_FOUND}(미존재·다른 학원 소속 둘 다 이 코드)
     */
    public StaffReportItemResponse detail(AuthUser requester, Long reportId) {
        ExceptionReport report = exceptionReportRepository.findByIdAndAcademyId(reportId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
        return toItems(List.of(report), requester.academyId()).get(0);
    }

    /**
     * 보고 목록을 항목으로 조립한다 — 회차·버스·보고자·탑승자·학생을 한 번에 읽어 쿼리 수가 보고 수에 비례하지
     * 않게 한다(BR-228, {@code EmergencyStaffQueryService#list} 와 같은 형태).
     */
    private List<StaffReportItemResponse> toItems(List<ExceptionReport> reports, Long academyId) {
        List<Long> runIds = reports.stream().map(ExceptionReport::getRunId).distinct().toList();
        Map<Long, Run> runsById = runRepository.findAllByIdInAndAcademyId(runIds, academyId).stream()
                .collect(Collectors.toMap(Run::getId, run -> run));
        Map<Long, Bus> busesById = busRepository
                .findAllByAcademyIdAndIdIn(academyId, runsById.values().stream().map(Run::getBusId).distinct().toList())
                .stream().collect(Collectors.toMap(Bus::getId, bus -> bus));
        Map<Long, Manager> reportersByAccountId = managerRepository
                .findAllByAcademyIdAndAccountIdIn(academyId,
                        reports.stream().map(ExceptionReport::getReportedBy).distinct().toList())
                .stream().collect(Collectors.toMap(Manager::getAccountId, manager -> manager));
        Map<Long, RunRider> ridersById = runRiderRepository.findAllByRunIdInAndAcademyId(runIds, academyId).stream()
                .collect(Collectors.toMap(RunRider::getId, rider -> rider));
        Map<Long, Student> studentsById = studentRepository
                .findAllByAcademyIdAndIdIn(academyId,
                        ridersById.values().stream().map(RunRider::getStudentId).distinct().toList())
                .stream().collect(Collectors.toMap(Student::getId, student -> student));

        return reports.stream()
                .map(report -> toItem(report, runsById, busesById, reportersByAccountId, ridersById, studentsById))
                .toList();
    }

    private StaffReportItemResponse toItem(ExceptionReport report, Map<Long, Run> runsById,
            Map<Long, Bus> busesById, Map<Long, Manager> reportersByAccountId, Map<Long, RunRider> ridersById,
            Map<Long, Student> studentsById) {
        Run run = required(runsById.get(report.getRunId()), "예외 보고의 회차가 없다 — runId=" + report.getRunId());
        Bus bus = required(busesById.get(run.getBusId()), "예외 보고의 버스가 없다 — busId=" + run.getBusId());
        Manager reporter = required(reportersByAccountId.get(report.getReportedBy()),
                "예외 보고자가 없다 — accountId=" + report.getReportedBy());
        String studentName = report.getType() == ExceptionReportType.GUARDIAN_ABSENT
                ? studentNameOf(report.getRunRiderId(), ridersById, studentsById)
                : null;

        return new StaffReportItemResponse(report.getId(), lower(report.getType()), report.getMemo(),
                report.getRunId(), bus.getBusNo(), studentName, reporter.getName(), report.getReportedAt(),
                false, null);
    }

    /** {@code guardian_absent} 보고의 대상 학생 이름 — {@code run_rider} → {@code student} 순서로 조인한다. */
    private String studentNameOf(Long runRiderId, Map<Long, RunRider> ridersById, Map<Long, Student> studentsById) {
        RunRider runRider = required(ridersById.get(runRiderId),
                "보호자 부재 보고의 탑승자가 없다 — runRiderId=" + runRiderId);
        return required(studentsById.get(runRider.getStudentId()),
                "보호자 부재 보고의 학생이 없다 — studentId=" + runRider.getStudentId()).getName();
    }

    private static <T> T required(T value, String message) {
        if (value == null) {
            throw new IllegalStateException(message);
        }
        return value;
    }

    /** enum → 소문자 문자열({@code ApprovalSummaryResponse} 의 {@code lower(Enum)} 관례와 같은 형태). */
    private static String lower(ExceptionReportType type) {
        return type.name().toLowerCase(Locale.ROOT);
    }
}
