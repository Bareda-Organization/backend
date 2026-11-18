package src.backend.exception.query;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import src.backend.exception.dto.StaffReportListResponse;
import src.backend.exception.entity.ExceptionReport;
import src.backend.exception.entity.ExceptionReportType;
import src.backend.exception.report.ExceptionReportItems;
import src.backend.exception.repository.ExceptionReportRepository;
import src.backend.exception.repository.ReportHandledCounts;
import src.backend.global.request.ApiValues;
import src.backend.global.request.PageParams;
import src.backend.global.security.AuthUser;

/**
 * 관계자용 예외 보고 조회(API_SPEC §5.20) — 목록·상세 둘 다 학원 범위로 좁힌다.
 *
 * <p>{@code student_name} 은 저장하지 않는다 — {@code type=guardian_absent} 일 때만 {@code run_rider_id}
 * 로 그 시점의 탑승자를 다시 조인해 채운다({@code ApprovalSummaryAssembler.toSummary} 와 같은 조회 시점
 * 조립 관례). 처리 여부는 {@code exception_report.handled_at}·{@code handled_by} 에서 읽고 처리자 이름만 조회
 * 시점에 계정에서 조인한다(Ruling 814).
 *
 * <p>{@code date} 필터는 {@code reported_at} 의 날짜 성분이다 — {@code run.service_date} 가 아니다
 * ({@link ExceptionReportRepository#search} javadoc 근거). 학원 자정 경계는 {@link Clock#getZone()}
 * (Asia/Seoul, {@code ClockConfig})으로 환산한다.
 */
@Service
@RequiredArgsConstructor
public class ExceptionReportQueryService {

    private final ExceptionReportRepository exceptionReportRepository;

    private final ExceptionReportItems reportItems;

    private final Clock clock;

    /**
     * {@code date} 필터가 없을 때 {@link ExceptionReportRepository#search} 에 넘길 극단 경계값 —
     * {@code reportedAt} 이 이 범위를 벗어날 수 없으므로 사실상 무제한이다. null 을 넘기지 않는 이유는
     * 그 메서드의 javadoc 참고(Postgres 파라미터 타입 추론 실패).
     */
    private static final OffsetDateTime UNBOUNDED_FROM = OffsetDateTime.parse("0001-01-01T00:00:00Z");

    private static final OffsetDateTime UNBOUNDED_TO = OffsetDateTime.parse("9999-12-31T23:59:59Z");

    /**
     * 목록(§5.20 목록) — {@code type}·{@code date}·{@code run_id}·{@code handled} 전부 선택적, 페이지네이션 없음 — 최근
     * 200건까지만 돌려준다(BR-228). 최상위 {@code counts} 는 {@code handled} 만 뺀 같은 조건의 건수다(Ruling 814).
     */
    public StaffReportListResponse list(AuthUser requester, String type, String date, Long runId, String handled) {
        ExceptionReportType parsedType = ApiValues.reportType(type);
        LocalDate parsedDate = ApiValues.date(date);
        Boolean parsedHandled = ApiValues.handledFilter(handled);

        OffsetDateTime from = UNBOUNDED_FROM;
        OffsetDateTime to = UNBOUNDED_TO;
        if (parsedDate != null) {
            ZoneId zone = clock.getZone();
            from = parsedDate.atStartOfDay(zone).toOffsetDateTime();
            to = parsedDate.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        }

        // 페이징이 없어 행 수를 상한으로 자른다(BR-228) — 최근 보고부터 UNPAGED_LIST_MAX 건.
        List<ExceptionReport> reports = exceptionReportRepository.search(requester.academyId(), parsedType, runId,
                from, to, parsedHandled, Limit.of(PageParams.UNPAGED_LIST_MAX));
        ReportHandledCounts counts = exceptionReportRepository.countByHandled(requester.academyId(), parsedType,
                runId, from, to);
        return StaffReportListResponse.of(reportItems.itemsOf(reports, requester.academyId()),
                new StaffReportListResponse.Counts(counts.getHandled(), counts.getUnhandled()));
    }
}
