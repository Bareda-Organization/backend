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
     */
    public Student requireGuardianOf(AuthUser parent, Long studentId) {
        return studentGuardianRepository.findByGuardianId(parent.userId()).stream()
                .map(StudentGuardian::getStudent)
                .filter(s -> s.getId().equals(studentId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN, "자녀가 아닙니다"));
    }
}
