package src.backend.student.query;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.bus.entity.Bus;
import src.backend.bus.repository.BusRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;
import src.backend.student.access.LinkedChildLookup;
import src.backend.student.access.StudentRunResolver;
import src.backend.student.dto.StudentBusPositionResponse;
import src.backend.student.entity.Student;

/**
 * 학부모 앱의 실시간 버스 위치(LOC-02, API_SPEC §3.11, 목표 9·11).
 *
 * <p>쿼리 파라미터가 없다(§3.11) — 항상 오늘 날짜로 {@link StudentRunResolver#resolveForToday} 를
 * 부른다. 오늘 그 학생 회차가 아예 없을 때의 코드는 §3.11 에러 사전에 없다(§3.10 은
 * {@code RUN_NOT_FOUND} 를 명시하지만 §3.11 은 침묵) — {@link ErrorCode#RUN_NOT_FOUND} 를 방어적으로
 * 재사용했고, 이 판단은 보고서에 남긴다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StudentBusPositionQueryService {

    /** Ruling 208 — 마지막 수신(§3.11 {@code received_at}) 후 이 이상 지나면 신호 유실로 본다. */
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(2);

    private final LinkedChildLookup linkedChildLookup;

    private final StudentRunResolver studentRunResolver;

    private final RunRiderRepository runRiderRepository;

    private final BusRepository busRepository;

    private final RunPositionCache runPositionCache;

    private final Clock clock;

    public StudentBusPositionResponse position(AuthUser requester, Long studentId) {
        Student student = linkedChildLookup.linkedChild(requester, studentId);
        Run run = studentRunResolver.resolveForToday(student.getAcademyId(), student.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        String busNo = busRepository.findByIdAndAcademyId(run.getBusId(), student.getAcademyId())
                .map(Bus::getBusNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.RUN_NOT_FOUND));
        String runStatus = run.getStatus().name().toLowerCase(Locale.ROOT);

        if (run.getStatus() != RunStatus.MOVING || isAbsentToday(run, student.getId())) {
            return StudentBusPositionResponse.withoutPosition(run.getId(), busNo, runStatus);
        }

        Optional<RunPositionSnapshot> snapshot = runPositionCache.find(run.getId());
        if (snapshot.isEmpty()) {
            return StudentBusPositionResponse.withoutPosition(run.getId(), busNo, runStatus);
        }
        RunPositionSnapshot position = snapshot.get();
        if (isStale(position.receivedAt())) {
            return new StudentBusPositionResponse(run.getId(), busNo, runStatus, null, null, null,
                    position.receivedAt(), null);
        }
        return new StudentBusPositionResponse(run.getId(), busNo, runStatus, position.lat(), position.lng(),
                position.receivedAt(), null, position.currentStopName());
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
