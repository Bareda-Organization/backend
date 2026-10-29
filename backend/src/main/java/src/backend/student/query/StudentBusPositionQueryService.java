package src.backend.student.query;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.policy.PolicyConstants;
import src.backend.global.security.AuthUser;
import src.backend.location.dto.RunPositionRedisValue;
import src.backend.location.infrastructure.RunPositionStore;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.run.repository.DelayNoticeRepository;
import src.backend.student.access.RouteStopSource;
import src.backend.student.access.StudentRunResolver;
import src.backend.student.access.StudentRunsAccess;
import src.backend.student.dto.StudentBusPositionResponse;
import src.backend.student.entity.Student;

/**
 * 학부모 앱의 실시간 버스 위치(LOC-02, API_SPEC §3.11).
 *
 * <p>쿼리 파라미터가 없다(§3.11) — 항상 오늘 날짜로 {@link StudentRunResolver#resolveForToday} 를
 * 부른다. 오늘 그 학생 회차가 아예 없으면 {@link ErrorCode#RUN_NOT_FOUND} 다(§3.11 에러 — §3.10 과
 * 같은 코드. 응답의 필수 필드 {@code run_id}·{@code bus_no} 를 채울 회차가 없다).
 *
 * <p>학부모(연결 자녀)와 학생(본인) 둘 다 부른다 — 판정은 {@link StudentRunsAccess}(BR-025).
 *
 * <p>클래스에 {@code @Transactional} 을 두지 않는다 — Redis 좌표 읽기({@code RunPositionStore}, 최대 명령 시간 상한
 * 500ms)가 트랜잭션 안에 있으면 그 동안 DB 연결을 쥔다. 저장소 호출마다 짧은 읽기 트랜잭션이 돈다.
 */
@Service
@RequiredArgsConstructor
public class StudentBusPositionQueryService {

    /**
     * Ruling 208 — 마지막 수신(§3.11 {@code received_at}) 후 이 이상 지나면 신호 유실로 본다.
     *
     * <p>값은 {@link PolicyConstants#POSITION_STALE_THRESHOLD} 한 곳에 있다 — 관제 판정 · 유실 집계가 같은 값을 쥐어야 한다.
     */
    public static final Duration STALE_THRESHOLD = PolicyConstants.POSITION_STALE_THRESHOLD;

    private final StudentRunsAccess studentRunsAccess;

    private final StudentRunResolver studentRunResolver;

    private final RunRiderRepository runRiderRepository;

    private final BusRepository busRepository;

    private final RunPositionStore runPositionStore;

    private final DelayNoticeRepository delayNoticeRepository;

    private final RouteStopSource routeStopSource;

    private final Clock clock;

    /** 학생 1명의 오늘 회차 버스 위치(§3.11) — 오늘 회차가 없으면 {@code 404 RUN_NOT_FOUND}(W03-16). */
    public StudentBusPositionResponse position(AuthUser requester, Long studentId) {
        Student student = studentRunsAccess.resolve(requester, studentId);
        Run run = studentRunResolver.resolveForToday(student.getAcademyId(), student.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        String busNo = busNoOf(run, student);
        // 당일 미등원이면 그 버스의 지연 안내도 의미가 없다 — 위치와 함께 비운다(§3.11 "오늘은 버스를 이용하지 않습니다")
        boolean absentToday = isAbsentToday(run, student.getId());
        StudentBusPositionResponse.Delay delay = absentToday ? null : lastDelayOf(run);
        if (run.getStatus() != RunStatus.MOVING || absentToday) {
            return withoutPosition(run, busNo, delay);
        }
        // Redis 가 죽으면 run_position 최신 행으로 대체된다(BR-167) — current_stop_name 은 정차 항목에서 같은 규칙으로 채워진다.
        Optional<RunPositionRedisValue> snapshot = runPositionStore.find(run.getId());
        return snapshot.isEmpty() ? withoutPosition(run, busNo, delay)
                : withPosition(run, busNo, delay, snapshot.get());
    }

    private String busNoOf(Run run, Student student) {
        return busRepository.findByIdAndAcademyId(run.getBusId(), student.getAcademyId())
                .map(Bus::getBusNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
    }

    private static StudentBusPositionResponse withoutPosition(Run run, String busNo,
            StudentBusPositionResponse.Delay delay) {
        return StudentBusPositionResponse.withoutPosition(run.getId(), busNo, statusOf(run), run.getStartedAt(),
                run.getFinishedAt(), delay);
    }

    /** 좌표를 싣는다 — 마지막 수신이 {@link #STALE_THRESHOLD} 를 넘었으면 좌표 대신 마지막 수신 시각만 싣는다(신호 유실). */
    private StudentBusPositionResponse withPosition(Run run, String busNo, StudentBusPositionResponse.Delay delay,
            RunPositionRedisValue position) {
        OffsetDateTime stopArrivedAt = lastArrivedAtOf(run);
        if (isStale(position.receivedAt())) {
            return new StudentBusPositionResponse(run.getId(), busNo, statusOf(run), null, null, null,
                    position.receivedAt(), position.currentStopName(), stopArrivedAt, run.getStartedAt(),
                    run.getFinishedAt(), delay);
        }
        return new StudentBusPositionResponse(run.getId(), busNo, statusOf(run), position.lat(), position.lng(),
                position.receivedAt(), null, position.currentStopName(), stopArrivedAt, run.getStartedAt(),
                run.getFinishedAt(), delay);
    }

    private static String statusOf(Run run) {
        return run.getStatus().name().toLowerCase(Locale.ROOT);
    }

    /** 끝나지 않은 회차의 마지막 지연 알림(§4.9) — 없거나 회차가 끝났으면 {@code null}(지연 안내 띠를 걷는다). */
    private StudentBusPositionResponse.Delay lastDelayOf(Run run) {
        if (run.getStatus() == RunStatus.FINISHED) {
            return null;
        }
        return delayNoticeRepository.findFirstByRunIdOrderByIdDesc(run.getId())
                .map(notice -> new StudentBusPositionResponse.Delay(notice.getMinutes(),
                        notice.getReason().name().toLowerCase(Locale.ROOT), notice.getSentAt()))
                .orElse(null);
    }

    /** 마지막으로 도착 처리된 승하차지의 도착 시각(§4.3 {@code current_stop} 과 같은 판정) — 도착 기록이 없으면 {@code null}. */
    private OffsetDateTime lastArrivedAtOf(Run run) {
        return routeStopSource.lastArrivedAt(run);
    }

    /** 당일 미등원이면 운행 중이어도 위치를 보이지 않는다(§3.11 "당일 미등원이면 위치 부재"). */
    private boolean isAbsentToday(Run run, Long studentId) {
        return runRiderRepository.findByRunIdAndStudentId(run.getId(), studentId)
                .map(rider -> rider.getStatus() == RiderStatus.ABSENT)
                .orElse(false);
    }

    private boolean isStale(OffsetDateTime receivedAt) {
        if (receivedAt == null) {
            return true;
        }
        return Duration.between(receivedAt, OffsetDateTime.now(clock)).compareTo(STALE_THRESHOLD) >= 0;
    }
}
