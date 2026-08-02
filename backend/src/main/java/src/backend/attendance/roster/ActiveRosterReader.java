package src.backend.attendance.roster;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import src.backend.attendance.repository.spec.AttendanceExceptionRepository;
import src.backend.global.common.ApprovalStatus;
import src.backend.student.entity.Student;
import src.backend.student.repository.spec.StudentRepository;

/**
 * "당일 실제 명단" 판정 한 곳 — 활성 학생 중 그 날짜에 <b>승인된</b> 결석·휴원이 없는 학생만 남긴다.
 * 기사 앱 명단({@code DriveSessionQueryService}) · 노선 계산({@code RoutingCommandService}) ·
 * 시뮬레이션({@code RoutePlanSimulationService}) 셋이 같은 명단을 봐야 하므로 규칙은 하나여야 한다.
 *
 * <p>{@code attendance/query/} 가 아니라 별도 패키지에 두는 이유: 이 조회를 필요로 하는 호출자에
 * Command 서비스({@link src.backend.routing.command.RoutingCommandService})가 섞여 있다.
 * {@code query/} 에 두면 reference.md §7 이 금지한 "Command 가 Query 를 호출" 이 그대로 생긴다 —
 * {@code student/access/GuardianAccess} 를 {@code query/} 밖에 둔 것과 같은 이유다(§3).
 *
 * <p>{@code @Transactional(readOnly = true)} 는 단독 호출(Query 서비스 경유)일 때만 새 읽기 전용
 * 트랜잭션을 연다. 쓰기 트랜잭션 안에서 불리면 REQUIRED 로 그 트랜잭션에 참여하므로,
 * 같은 트랜잭션에서 방금 바꾼 학생 좌표가 auto-flush 되어 이 조회 결과에 반영된다 —
 * {@code LocationChangeCommandService} 의 좌표 갱신 → 재계산 순서가 이 성질에 기대고 있다.
 */
@Component
public class ActiveRosterReader {

    private final StudentRepository studentRepository;
    private final AttendanceExceptionRepository attendanceExceptionRepository;

    public ActiveRosterReader(StudentRepository studentRepository,
                              AttendanceExceptionRepository attendanceExceptionRepository) {
        this.studentRepository = studentRepository;
        this.attendanceExceptionRepository = attendanceExceptionRepository;
    }

    /** 버스에 배정된 학생 기준 — 기사 앱 명단·노선 계산·시뮬레이션이 쓴다. */
    @Transactional(readOnly = true)
    public List<Student> forBus(Long busId, LocalDate date) {
        return excludeApprovedExceptions(studentRepository.findByAssignedBusIdAndActiveTrue(busId), date);
    }

    /**
     * 테넌트 전체 활성 학생 기준 — F4 자동배차가 "이 학원 학생 중 오늘 등하원해야 하는 학생"을 구할 때 쓴다.
     * {@link #forBus} 와 달리 배정된 버스가 없는 학생도 포함한다(배차 대상을 찾는 게 목적이라서).
     */
    @Transactional(readOnly = true)
    public List<Student> forTenant(Long tenantId, LocalDate date) {
        return excludeApprovedExceptions(studentRepository.findByTenantIdAndActiveTrue(tenantId), date);
    }

    /** 신청(PENDING)·반려(REJECTED) 는 제외 사유가 아니다 — 승인된 건만 명단에서 뺀다. */
    private List<Student> excludeApprovedExceptions(List<Student> candidates, LocalDate date) {
        return candidates.stream()
                .filter(student -> attendanceExceptionRepository
                        .findByStudentIdAndTargetDate(student.getId(), date).stream()
                        .noneMatch(exception -> exception.getStatus() == ApprovalStatus.APPROVED))
                .toList();
    }
}
