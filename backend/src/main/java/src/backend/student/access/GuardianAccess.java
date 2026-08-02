package src.backend.student.access;

import org.springframework.stereotype.Component;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.student.entity.Student;
import src.backend.student.entity.StudentGuardian;
import src.backend.student.repository.spec.StudentGuardianRepository;

/**
 * "이 사람이 이 학생의 보호자인가" 접근 검증 컴포넌트.
 * attendance·schedule 두 모듈 세 서비스(결석·휴원 신고, 등하원 시간 변경, 위치 변경)가 같은
 * 보호자 판정을 각자 복붙해 갖고 있으면, 조건이 하나 늘 때 한 곳을 놓쳐도 컴파일 에러 없이
 * 권한 우회 구멍이 생긴다. {@link src.backend.bus.access.BusCrewGuard} 와 같은 목적으로
 * 판정 지점을 여기 하나로 모으지만, 판정에 {@link StudentGuardianRepository} 조회가 필요해
 * static 유틸이 아니라 스프링 빈이다.
 *
 * <p>student 도메인의 {@code query/} 가 아니라 {@code access/} 에 두는 이유: 호출자가 전부
 * Command 서비스라 {@code query/} 에 두면 {@code attendance.command → student.query} 호출이
 * 새로 생기고, 이는 reference.md §7 이 금지한 "Command 가 Query 를 호출" 패턴이 된다.
 * {@code access/} 는 command 도 query 도 아닌 정책 계층이라 이 충돌을 피한다.
 */
@Component
public class GuardianAccess {

    private final StudentGuardianRepository studentGuardianRepository;

    public GuardianAccess(StudentGuardianRepository studentGuardianRepository) {
        this.studentGuardianRepository = studentGuardianRepository;
    }

    /**
     * {@code parent} 가 {@code studentId} 학생의 보호자인지 검증하고 그 학생을 반환한다.
     * 반환 타입을 {@link Student} 엔티티로 둔 이유: 호출부 3곳이 전부 반환된 학생의
     * {@code getTenant().getId()}·{@code getAssignedBus()} 를 이어서 쓴다 — 검증과 조회를
     * 한 번에 끝내려는 의도다.
     *
     * <p>퇴원({@code active = false}) 학생은 자녀가 맞아도 거부한다 — 호출자 3곳(결석·휴원 신고,
     * 등하원 시간 변경, 위치 변경)이 전부 "재학 중인 학생의 등하원"을 전제로 한 신규 신청이라,
     * 배차·시뮬레이션에는 반영되지 않는 무효 요청이 접수만 되는 것을 막는다. "자녀가 아님"과 메시지를
     * 분리한 이유: 둘 다 뭉뚱그리면 학부모가 앱에서 왜 막혔는지 알 수 없고, 이 저장소는
     * {@code GlobalExceptionHandler.handleBusiness()} 에서 {@link BusinessException} 을 로깅하지 않아
     * 서버 로그로도 구분할 수 없다. 과거 이력 조회(Query 서비스)는 이 메서드를 거치지 않으므로
     * 퇴원 후에도 계속 보인다 — 여기서 막는 것은 신규 신청뿐이다.
     */
    public Student requireGuardianOf(AuthUser parent, Long studentId) {
        Student student = studentGuardianRepository.findByGuardianId(parent.userId()).stream()
                .map(StudentGuardian::getStudent)
                .filter(s -> s.getId().equals(studentId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN, "자녀가 아닙니다"));

        if (!student.isActive()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "퇴원 처리된 학생은 신청할 수 없습니다");
        }
        return student;
    }
}
