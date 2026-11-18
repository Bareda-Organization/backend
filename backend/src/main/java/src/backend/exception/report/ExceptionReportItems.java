package src.backend.exception.report;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.account.entity.Account;
import src.backend.boarding.entity.RunRider;
import src.backend.bus.entity.Bus;
import src.backend.exception.dto.StaffReportItemResponse;
import src.backend.exception.entity.ExceptionReport;
import src.backend.exception.entity.ExceptionReportType;
import src.backend.exception.repository.ExceptionReportRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.manager.entity.Manager;
import src.backend.run.entity.Run;

/**
 * 예외 보고를 관계자 화면의 항목 모양으로 조립한다(API_SPEC §5.20) — 목록 조회와 처리 표시 응답이 함께 쓰므로 조회 서비스가 아니라 이 패키지에 둔다
 * (쓰기 서비스가 조회 서비스를 부르지 않게, CODE_CONVENTIONS §3 의 공유 읽기 규칙).
 *
 * <p>{@code student_name} 은 저장하지 않는다 — {@code type=guardian_absent} 일 때만 {@code run_rider_id} 로 그 시점의 탑승자를 다시 조인해 채운다.
 * 처리 여부는 {@code exception_report.handled_at}·{@code handled_by} 에서 읽고 처리자 이름만 조회 시점에 계정에서 조인한다(Ruling 814).
 */
@Component
@RequiredArgsConstructor
public class ExceptionReportItems {

    private final ExceptionReportRepository exceptionReportRepository;

    private final ExceptionReportLookupLoader lookupLoader;

    /** 보고 1건을 항목으로 조립한다 — 남의 학원·없는 보고는 {@code 404 REPORT_NOT_FOUND}. */
    public StaffReportItemResponse item(Long academyId, Long reportId) {
        ExceptionReport report = exceptionReportRepository.findByIdAndAcademyId(reportId, academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
        return itemsOf(List.of(report), academyId).get(0);
    }

    /**
     * 보고 목록을 항목으로 조립한다 — 회차·버스·보고자·탑승자·학생을 한 번에 읽어 쿼리 수가 보고 수에 비례하지 않게 한다(BR-228,
     * {@code EmergencyStaffQueryService#list} 와 같은 형태).
     */
    public List<StaffReportItemResponse> itemsOf(List<ExceptionReport> reports, Long academyId) {
        ExceptionReportLookup lookup = lookupLoader.load(reports, academyId);
        return reports.stream().map(report -> toItem(report, lookup)).toList();
    }

    private StaffReportItemResponse toItem(ExceptionReport report, ExceptionReportLookup lookup) {
        Run run = required(lookup.runsById().get(report.getRunId()),
                "예외 보고의 회차가 없다 — runId=" + report.getRunId());
        Bus bus = required(lookup.busesById().get(run.getBusId()), "예외 보고의 버스가 없다 — busId=" + run.getBusId());
        Manager reporter = required(lookup.reportersByAccountId().get(report.getReportedBy()),
                "예외 보고자가 없다 — accountId=" + report.getReportedBy());
        String studentName = report.getType() == ExceptionReportType.GUARDIAN_ABSENT
                ? studentNameOf(report.getRunRiderId(), lookup)
                : null;

        Account handler = report.isHandled() ? lookup.handlersById().get(report.getHandledBy()) : null;

        return new StaffReportItemResponse(report.getId(), lower(report.getType()), report.getMemo(),
                report.getRunId(), bus.getBusNo(), studentName, reporter.getName(),
                reporter.getRole().name().toLowerCase(Locale.ROOT), report.getReportedAt(), report.isHandled(),
                report.getHandledAt(), handler == null ? null : handler.getName());
    }

    /** {@code guardian_absent} 보고의 대상 학생 이름 — {@code run_rider} → {@code student} 순서로 조인한다. */
    private String studentNameOf(Long runRiderId, ExceptionReportLookup lookup) {
        RunRider runRider = required(lookup.ridersById().get(runRiderId),
                "보호자 부재 보고의 탑승자가 없다 — runRiderId=" + runRiderId);
        return required(lookup.studentsById().get(runRider.getStudentId()),
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
