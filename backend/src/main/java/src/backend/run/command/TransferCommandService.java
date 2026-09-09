package src.backend.run.command;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Weekday;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.global.security.access.AcademyScope;
import src.backend.request.domain.ChangeWindow;
import src.backend.request.domain.ChangeWindowPolicy;
import src.backend.request.repository.BoardingIntentRepository;
import src.backend.routing.entity.Route;
import src.backend.routing.entity.RouteStop;
import src.backend.routing.repository.RouteRepository;
import src.backend.routing.repository.RouteStopRepository;
import src.backend.run.dto.TransferCapacityDetail;
import src.backend.run.dto.TransferRequest;
import src.backend.run.dto.TransferResponse;
import src.backend.run.dto.TransferResponse.FromImpact;
import src.backend.run.dto.TransferResponse.ToImpact;
import src.backend.run.dto.TransferResponse.TransferImpact;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunTransfer;
import src.backend.run.repository.RunForcedAdditionRepository;
import src.backend.run.repository.RunRepository;
import src.backend.run.repository.RunTransferRepository;
import src.backend.student.command.AddressVerification;
import src.backend.student.entity.Student;
import src.backend.student.geocoding.spec.GeocodedPoint;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentDailyStop;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * 관계자의 버스 간 이동(RTE-07, API_SPEC §5.8, Ruling 256) — 학생 1명을 출발 회차 명단에서 빼
 * 도착 회차 명단에 넣는 신청을 접수한다.
 *
 * <p><b>{@code @Transactional} 이 없는 것이 이 클래스의 요점이다</b> — {@code address} 경로면
 * {@link AddressVerification} 이 외부 지오코딩을 호출한다({@link ForcedAdditionCommandService} 와
 * 같은 근거, §7 규칙 16). "구간 판정 → 명단 소속 판정 → 정원 판정" 은 여기서 트랜잭션 밖에 두고,
 * 저장(+ {@code stop_id} 확정)만 {@link TransferStore} 의 짧은 트랜잭션에 맡긴다.
 *
 * <p>§5.8 은 {@link ForcedAdditionCommandService} 와 같은 <b>① 구간 전용</b>이되, 대상 회차가
 * 둘이라 <b>둘 중 하나라도</b> ①구간을 벗어나면 막는다(사양 본문 "둘 중 한 회차라도 ② 구간이면").
 */
@Service
@RequiredArgsConstructor
public class TransferCommandService {

    private final RunRepository runRepository;

    private final BusRepository busRepository;

    private final RouteRepository routeRepository;

    private final RouteStopRepository routeStopRepository;

    private final WeeklyAddressRepository weeklyAddressRepository;

    private final BoardingIntentRepository boardingIntentRepository;

    private final RunForcedAdditionRepository runForcedAdditionRepository;

    private final RunTransferRepository runTransferRepository;

    private final StudentRepository studentRepository;

    private final StopRepository stopRepository;

    private final AddressVerification addressVerification;

    private final TransferStore transferStore;

    private final Clock clock;

    public TransferResponse transfer(AuthUser requester, Long studentId, TransferRequest request) {
        // {id} 는 학생이다(§5.8 경로) — 존재 비노출(Ruling 163)이라 타 학원 학생도 404.
        Student student = studentRepository.findByIdAndAcademyIdAndDeletedAtIsNull(studentId, requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.STUDENT_NOT_FOUND));

        if (request.fromRunId().equals(request.toRunId())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        Run fromRun = runRepository.findByIdAndAcademyId(request.fromRunId(), requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        // 도착 회차는 타 학원이면 부재를 숨기지 않고 403 으로 답한다(사양 본문 — from 과 다른 판정).
        // 관계자가 자기 화면에서 고른 회차 id 라 "존재하지만 접근 불가" 를 그대로 알려주는 쪽이
        // "왜 안 되는지" 를 알 수 있게 한다는 판단으로 보인다(§7 규칙 16 계열의 절별 예외).
        Run toRun = runRepository.findById(request.toRunId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        AcademyScope.assertAccessible(requester, toRun.getAcademyId());

        assertWindowOpen(fromRun);
        assertWindowOpen(toRun);

        Set<Long> fromRoster = projectedRosterStudentIds(fromRun);
        if (!fromRoster.contains(student.getId())) {
            throw new BusinessException(ErrorCode.STUDENT_NOT_IN_RUN);
        }

        if (runTransferRepository.existsStagedByStudentIdAndAcademyId(student.getId(), requester.academyId())) {
            throw new BusinessException(ErrorCode.TRANSFER_ALREADY_STAGED);
        }

        assertStopOrAddressExclusive(request);

        Bus toBus = busRepository.findByIdAndAcademyId(toRun.getBusId(), requester.academyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.BUS_NOT_FOUND));
        long toCurrentCount = projectedRosterStudentIds(toRun).size()
                + runTransferRepository.countStagedByToRunIdAndAcademyId(toRun.getId(), requester.academyId());
        if (toCurrentCount + 1 > toBus.getStudentCapacity()) {
            throw new BusinessException(ErrorCode.CAPACITY_EXCEEDED,
                    new TransferCapacityDetail(toCurrentCount, toBus.getStudentCapacity()));
        }

        GeocodedPoint point = null;
        if (request.stopId() != null) {
            // 도착 회차 노선의 기존 승하차지가 아니라 <b>학원 안 전체</b>에서 찾는다 — §5.8 은 "도착
            // 회차 노선의 기존 승하차지" 라고 적었지만, 강제 추가(§5.7)도 노선 소속이 아니라 학원
            // 승하차지 전체를 대상으로 매칭한다(STU-05) — 노선 소속으로 좁히면 그 정류장이 아직
            // 이 노선에 배정되지 않은 경우까지 막혀, 강제 추가와 다른 기준이 된다.
            stopRepository.findAllByAcademyIdAndIdIn(requester.academyId(), List.of(request.stopId())).stream()
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.STOP_NOT_FOUND));
        } else {
            point = addressVerification.verifySingle(request.address());
        }

        RunTransfer transfer = transferStore.stage(fromRun, toRun, student, request, point, requester.accountId(),
                OffsetDateTime.now(clock));

        TransferImpact impact = new TransferImpact(
                new FromImpact(fromRoster.size(), fromRoster.size() - 1),
                new ToImpact(toCurrentCount, toCurrentCount + 1, toBus.getStudentCapacity()));
        return TransferResponse.of(transfer, impact);
    }

    private void assertWindowOpen(Run run) {
        ChangeWindow window = ChangeWindowPolicy.segmentOf(run, OffsetDateTime.now(clock));
        if (window != ChangeWindow.IMMEDIATE) {
            // ①구간 전용(§5.8) — 둘 중 한 회차라도 ②구간이면 막는다(사양 본문).
            throw new BusinessException(ErrorCode.CHANGE_WINDOW_CLOSED);
        }
    }

    private static void assertStopOrAddressExclusive(TransferRequest request) {
        boolean hasStop = request.stopId() != null;
        boolean hasAddress = request.address() != null && !request.address().isBlank();
        if (hasStop == hasAddress) {
            // 둘 다 없거나 둘 다 있으면 어느 쪽으로 처리할지 정할 수 없다.
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    /**
     * 회차의 당일 명단(§5.8 "확정 전이면 boarding_intent·요일별 주소 기준 예정 명단") — 정원 판정에도
     * 이 크기를 쓴다.
     *
     * <p>사양 문면은 강제 추가를 명시하지 않지만, 강제 추가로 이 회차에 이미 대기 중인 학생을 뺐다.
     * 빼면 그 학생이 "이 회차 명단에 없다" 는 이유로 이 회차 밖으로는 다시 이동시킬 수 없게 되어,
     * {@link RunConfirmationService#confirmOne} 이 실제로 반영하는 명단(요일별 주소 + 강제 추가 병합,
     * {@code applyOutgoingTransfers} 가 같은 두 자료구조에서 뺀다)과도 어긋난다 — 그 확정 배치가 쓰는
     * "당일 명단" 정의를 그대로 재사용한다.
     *
     * <p>같은 이유로 <b>이미 대기 중인 이동 건은 포함하지 않는다</b> — 그 건은 도착 회차의 확정 배치가
     * 반영해야 비로소 그 회차 명단의 일원이 되고, 그 전까지는 "예정" 이 아니라 "신청 중" 이다.
     */
    private Set<Long> projectedRosterStudentIds(Run run) {
        Set<Long> studentIds = new HashSet<>();
        Weekday weekday = weekdayOf(run.getServiceDate());
        Optional<Route> route = routeRepository.findByAcademyIdAndBusIdAndWeekdayAndDirection(run.getAcademyId(),
                run.getBusId(), weekday, run.getDirection());
        if (route.isPresent()) {
            List<RouteStop> routeStops = routeStopRepository.findAllOrderedByRouteIdAndAcademyId(route.get().getId(),
                    run.getAcademyId());
            if (!routeStops.isEmpty()) {
                List<Long> stopIds = routeStops.stream().map(RouteStop::getStopId).toList();
                Set<Long> excluded = new HashSet<>(
                        boardingIntentRepository.findStudentIdsByRunIdAndRidingFalse(run.getId()));
                weeklyAddressRepository.findDailyStopsByStopIds(run.getAcademyId(), stopIds, weekday,
                        run.getDirection())
                        .stream()
                        .map(StudentDailyStop::getStudentId)
                        .filter(id -> !excluded.contains(id))
                        .forEach(studentIds::add);
            }
        }
        runForcedAdditionRepository.findAllByRunIdAndAcademyId(run.getId(), run.getAcademyId())
                .forEach(forcedAddition -> studentIds.add(forcedAddition.getStudentId()));
        return studentIds;
    }

    /** {@code RunConfirmationService.weekdayOf} 와 같은 계산 — 시계를 보지 않고 날짜에서 바로 얻는다. */
    private static Weekday weekdayOf(LocalDate serviceDate) {
        return Weekday.valueOf(serviceDate.getDayOfWeek().name().substring(0, 3).toUpperCase(Locale.ROOT));
    }
}
