package src.backend.student.query;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.rider.RunRiderReader;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Weekday;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.request.ApiValues;
import src.backend.global.security.AuthUser;
import src.backend.request.query.BoardingIntentReadQueryService;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.student.access.StudentRunResolver;
import src.backend.student.access.StudentRunsAccess;
import src.backend.student.dto.StudentRunsResponse;
import src.backend.student.entity.Stop;
import src.backend.student.entity.Student;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentDailyStop;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * 자녀·본인 당일 회차 목록 조회(API_SPEC §3.5, P-04 · S-01).
 *
 * <p>{@code stop} 판정은 {@link StudentRunResolver#resolveAllByDate} 가 이미 {@code belongsTo} 로
 * 소속을 확인한 회차만 넘겨준다는 전제를 그대로 쓴다 — idle 회차는 고정 노선(요일별 주소)에 이 학생의
 * 정차지가 반드시 있고, 확정 이후 회차는 {@code run_rider} 행이 반드시 있다. 그래도 어긋나면(불변식
 * 위반) 방어적으로 {@code RUN_NOT_FOUND} 를 던진다({@code StudentRouteQueryService.myStopId} 와 같은
 * 방어) — §3.5 는 이 에러를 문서화하지 않는다. 정상 데이터에서는 도달하지 않는 분기이기 때문이다.
 *
 * <p>{@code riding}·{@code rider_status}·{@code change_quota_left} 는 {@code boarding_intent} 행이
 * 없을 때의 기본값을 {@code BoardingIntentCommandService.riderStatusOf}·{@code quotaLeftOf} 와 같은
 * 규칙으로 합성한다(탑승 ON 기본 → {@code riding=true} 면 {@code waiting}, 아니면 {@code absent};
 * 한도는 미사용 기본이라 {@code changeQuotaLeft=1}).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StudentRunsQueryService {

    private final StudentRunsAccess studentRunsAccess;

    private final StudentRunResolver studentRunResolver;

    private final RunRiderReader runRiderReader;

    private final BoardingIntentReadQueryService boardingIntentReadQueryService;

    private final WeeklyAddressRepository weeklyAddressRepository;

    private final StopRepository stopRepository;

    private final BusRepository busRepository;

    private final Clock clock;

    /** 학생 1명의 그날 회차 목록(§3.5) — {@code rawDate} 미지정이면 오늘. */
    public StudentRunsResponse runs(AuthUser requester, Long studentId, String rawDate) {
        Student student = studentRunsAccess.resolve(requester, studentId);
        LocalDate date = rawDate == null ? LocalDate.now(clock) : ApiValues.date(rawDate);
        List<Run> runList = studentRunResolver.resolveAllByDate(student.getAcademyId(), student.getId(), date);

        List<RunContext> contexts = runList.stream().map(run -> toContext(run, student.getId())).toList();
        Map<Long, Stop> stopsById = stopRepository
                .findAllByAcademyIdAndIdIn(student.getAcademyId(),
                        contexts.stream().map(RunContext::stopId).toList())
                .stream().collect(Collectors.toMap(Stop::getId, s -> s));

        List<StudentRunsResponse.Item> items = new ArrayList<>();
        for (RunContext context : contexts) {
            items.add(toItem(context, stopsById));
        }
        return new StudentRunsResponse(items);
    }

    /** 회차마다 필요한 {@code run_rider} 조회를 한 번만 하기 위한 중간 묶음 — 정차지·탑승 상태가 둘 다 여기서 나온다. */
    private record RunContext(Run run, Long studentId, Long stopId, Optional<RunRiderReader.Entry> rider) {
    }

    private RunContext toContext(Run run, Long studentId) {
        if (run.getStatus() == RunStatus.IDLE) {
            Weekday weekday = Weekday.of(run.getServiceDate());
            Long stopId = weeklyAddressRepository
                    .findDailyStops(run.getAcademyId(), List.of(studentId), weekday, run.getDirection()).stream()
                    .findFirst().map(StudentDailyStop::getStopId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
            return new RunContext(run, studentId, stopId, Optional.empty());
        }
        RunRiderReader.Entry rider = runRiderReader.findRider(run.getId(), studentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        return new RunContext(run, studentId, rider.stopId(), Optional.of(rider));
    }

    private StudentRunsResponse.Item toItem(RunContext context, Map<Long, Stop> stopsById) {
        Run run = context.run();
        String busNo = busRepository.findByIdAndAcademyId(run.getBusId(), run.getAcademyId()).map(Bus::getBusNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        BoardingIntentReadQueryService.Intent intent = boardingIntentReadQueryService.findIntent(run.getId(),
                context.studentId());
        boolean riding = intent.riding();
        int changeQuotaLeft = intent.hasChangeQuota() ? 1 : 0;
        String riderStatus = context.rider().map(r -> statusNameOf(r.status()))
                .orElse(statusNameOf(riding ? RiderStatus.WAITING : RiderStatus.ABSENT));
        Stop stop = stopsById.get(context.stopId());
        if (stop == null) {
            throw new BusinessException(ErrorCode.RUN_NOT_FOUND);
        }
        StudentRunsResponse.Stop stopDto = new StudentRunsResponse.Stop(stop.getId(), stop.getName(),
                stop.getAddress());
        return new StudentRunsResponse.Item(run.getId(), nameOf(run.getDirection()), busNo, run.getDepartTime(),
                nameOf(run.getStatus()), run.getStatus() != RunStatus.IDLE, riding, riderStatus, stopDto,
                changeQuotaLeft);
    }

    private static String statusNameOf(RiderStatus status) {
        return status.name().toLowerCase(Locale.ROOT);
    }

    private static String nameOf(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
