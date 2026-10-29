package src.backend.request.query;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.global.common.enums.Weekday;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.request.dto.ApprovalSummaryResponse;
import src.backend.request.entity.ChangeRequest;
import src.backend.run.entity.Run;
import src.backend.student.entity.Stop;
import src.backend.student.entity.Student;
import src.backend.student.repository.StopRepository;
import src.backend.student.repository.StudentDailyStop;
import src.backend.student.repository.StudentRepository;
import src.backend.student.repository.WeeklyAddressRepository;

/**
 * 승인 요약({@link ApprovalSummaryResponse}) 조립 — 목록 항목과 상세 상단이 같은 요약을 쓴다(API_SPEC §5.5).
 * 요청 대상 학생의 승하차지 이름·남는 인원·제거 여부(필수 3필드)를 명단에서 되짚는다.
 */
@Component
@RequiredArgsConstructor
class ApprovalSummaryAssembler {

    private final BusRepository busRepository;

    private final StudentRepository studentRepository;

    private final StopRepository stopRepository;

    private final WeeklyAddressRepository weeklyAddressRepository;

    /** 요약 하나를 만드는 데 필요한 요청·회차·명단·승하차지 묶음. */
    record SummaryContext(ChangeRequest cr, Run run, List<RunRider> riders, Map<Long, Stop> stops, Long academyId) {
    }

    /** 상세(§5.5 상세)용 요약 — 한 건이라 버스·학생·승하차지를 그 자리에서 읽는다. */
    ApprovalSummaryResponse toSummary(ChangeRequest cr, Run run, List<RunRider> riders, Long academyId) {
        Bus bus = busRepository.findByIdAndAcademyId(run.getBusId(), academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BUS_NOT_FOUND));
        Student student = studentRepository.findAllByAcademyIdAndIdIn(academyId, List.of(cr.getStudentId())).stream()
                .findFirst().orElseThrow(() -> new BusinessException(ErrorCode.STUDENT_NOT_FOUND));
        SummaryContext context = new SummaryContext(cr, run, riders,
                stopsOf(List.of(cr), List.of(riders), academyId), academyId);
        return toSummary(context, bus.getBusNo(), student.getName());
    }

    /** 목록 항목용 요약 — 버스 번호·학생 이름·승하차지는 호출자가 일괄 조회해 넘긴다. */
    ApprovalSummaryResponse toSummary(SummaryContext context, String busNo, String studentName) {
        ChangeRequest cr = context.cr();
        Optional<RunRider> mine = context.riders().stream()
                .filter(r -> r.getStudentId().equals(cr.getStudentId()))
                .findFirst();
        SubjectStop subject = mine.isPresent()
                ? liveSubjectStopOf(mine.get(), context)
                : decidedSubjectStopOf(context);
        return ApprovalSummaryResponse.of(cr, studentName, busNo, context.run().getDirection(),
                cr.getDeadlineAt(), subject.stopName(), subject.remainingRiders(), subject.willRemoveStop());
    }

    /** 목록이 표시할 승하차지 전부 — 명단의 승하차지와 이동 요청의 목적지를 한 번에 읽는다(BR-075). */
    Map<Long, Stop> stopsOf(List<ChangeRequest> requests, Collection<List<RunRider>> riders,
            Long academyId) {
        Set<Long> stopIds = new HashSet<>();
        riders.forEach(list -> list.forEach(rider -> stopIds.add(rider.getStopId())));
        requests.stream().map(ChangeRequest::getNewStopId).filter(Objects::nonNull).forEach(stopIds::add);
        return stopRepository.findAllByAcademyIdAndIdIn(academyId, List.copyOf(stopIds)).stream()
                .collect(Collectors.toMap(Stop::getId, stop -> stop));
    }

    private SubjectStop liveSubjectStopOf(RunRider mine, SummaryContext context) {
        Stop stop = Optional.ofNullable(context.stops().get(mine.getStopId()))
                .orElseThrow(() -> new IllegalStateException("승하차지가 없다 — stopId=" + mine.getStopId()));
        long remaining = context.riders().stream()
                .filter(r -> !r.getStudentId().equals(context.cr().getStudentId()))
                .filter(r -> mine.getStopId().equals(r.getStopId()))
                .filter(r -> r.getStatus() != RiderStatus.ABSENT)
                .count();
        return new SubjectStop(stop.getName(), (int) remaining, remaining == 0);
    }

    /**
     * 이 학생이 지금 그 회차 명단에 없는 경우 — {@code rejected}·{@code auto_rejected} 는 이것이
     * 정상이다({@code reject}·{@code autoReject} 는 {@code RunRider} 를 건드리지 않는다). API_SPEC
     * §5.5 목록의 "명단에 없을 때" 표(Ruling 265)가 이 상황과 아래 우선순위를 정본에
     * 명시한다 — "이 학생이 지금 타고 있는 승하차지를 비우면 어떻게 되는가" 라는 질문 자체가 성립하지
     * 않으므로, 저장된 값만으로 "이 요청이 가리키던 승하차지" 를 최선으로 되짚는다(§5.5 가 명시한
     * "재최적화 없이 저장된 값·단순 집계만" 취지를 따름).
     *
     * <p>우선순위 — ① RELOCATE 의 확정된 목적지({@code new_stop_id}) ② 그 요일·방향의 등록 주소
     * ({@code weekly_address}, CANCEL 포함 전 유형을 커버) ③ RELOCATE 의 미확정 주소 원문
     * ({@code new_address}, {@code Stop} 조회는 안 되므로 표시용 문자열 그대로) ④ 그마저 없으면
     * 플레이스홀더.
     *
     * <p>결정이 이미 끝난 건이라 "승인 시 제거되는가" 자체가 성립하지 않는다 — {@code will_remove_stop}
     * 은 이 경로에서 항상 {@code false}. 상태값으로 분기하지 않고 "명단에 있는가" 로만 가른 이유는
     * {@code approved} 도 구조적으로만 명단에 있는 것이 보장될 뿐이라, 가정이 깨지는 경우(예: 향후
     * 다른 승인 경로가 추가돼 명단을 지우는 경우)에도 이 경로가 방어선이 되게 하기 위함이다.
     */
    private SubjectStop decidedSubjectStopOf(SummaryContext context) {
        ChangeRequest cr = context.cr();
        Long resolvedStopId = resolvedStopIdOf(context);
        Optional<Stop> stop = resolvedStopId == null ? Optional.empty() : stopOf(resolvedStopId, context);
        if (stop.isPresent()) {
            return new SubjectStop(stop.get().getName(), remainingAt(resolvedStopId, context.riders()), false);
        }
        if (cr.getNewAddress() != null) {
            return new SubjectStop(cr.getNewAddress(), 0, false);
        }
        return new SubjectStop("배정 정보 없음", 0, false);
    }

    /** 요청이 가리키던 승하차지 id — 확정된 목적지, 없으면 그 요일·방향의 등록 주소, 둘 다 없으면 null. */
    private Long resolvedStopIdOf(SummaryContext context) {
        ChangeRequest cr = context.cr();
        if (cr.getNewStopId() != null) {
            return cr.getNewStopId();
        }
        Run run = context.run();
        Weekday weekday = Weekday.of(run.getServiceDate());
        return weeklyAddressRepository
                .findDailyStops(context.academyId(), List.of(cr.getStudentId()), weekday, run.getDirection())
                .stream()
                .map(StudentDailyStop::getStopId)
                .findFirst()
                .orElse(null);
    }

    private Optional<Stop> stopOf(Long stopId, SummaryContext context) {
        // 요일별 주소에서 찾은 승하차지는 명단·이동 목적지에 없을 수 있어 그때만 따로 읽는다.
        return Optional.ofNullable(context.stops().get(stopId))
                .or(() -> stopRepository.findAllByAcademyIdAndIdIn(context.academyId(), List.of(stopId)).stream()
                        .findFirst());
    }

    private int remainingAt(Long stopId, List<RunRider> riders) {
        return (int) riders.stream()
                .filter(r -> stopId.equals(r.getStopId()))
                .filter(r -> r.getStatus() != RiderStatus.ABSENT)
                .count();
    }

    /** {@link #liveSubjectStopOf}·{@link #decidedSubjectStopOf} 가 채우는 3필드 묶음(§5.5 목록 필수). */
    private record SubjectStop(String stopName, int remainingRiders, boolean willRemoveStop) {
    }

}
