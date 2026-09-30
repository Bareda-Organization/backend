package src.backend.student.access;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.common.enums.Role;
import src.backend.global.security.AuthUser;
import src.backend.student.entity.Student;
import src.backend.student.repository.StudentRepository;

/**
 * §3.5 · §3.10 · §3.11 학생 조회의 접근 판정 — 문서화된 권한이 "학부모(연결 자녀) ·
 * 학생(본인)" 둘이라 {@link LinkedChildLookup}(학부모 전용) 하나로는 못 덮는다.
 *
 * <p>학생 본인 판정은
 * {@link StudentRepository#findByAccountId} 로 요청자 계정에 연결된 학생 레코드를 찾고, 그 id 를
 * 경로의 {@code studentId} 와 대조하는 것으로 충분하다 — 다른 계정의 {@code accountId} 를 빌려 오는
 * 경로가 없어({@code AuthUser} 가 토큰에서 나온다) 우회로가 부재하다.
 *
 * <p>{@link src.backend.global.security.authz.Permissions#STUDENT_READ_BASIC} 은 관계자·기사·동승자·
 * 메인관리자까지 보유해 애너테이션
 * 만으로는 이 좁은 권한을 표현할 수 없다({@code CanReadStudentRoute} 의 자바독과 같은 판단) — 그래서
 * PARENT·STUDENT 가 아닌 역할은 이 클래스가 직접 {@code 403} 으로 막는다.
 */
@Component
@RequiredArgsConstructor
public class StudentRunsAccess {

    private final LinkedChildLookup linkedChildLookup;

    private final StudentRepository studentRepository;

    /**
     * 요청자가 이 학생에 닿을 수 있는지 판정하고, 닿으면 그 학생 레코드를 돌려준다.
     *
     * <p>학부모는 {@link LinkedChildLookup} 에 그대로 위임한다(연결 부재 {@code 403} · 퇴원생
     * {@code 404}, 그 판정 순서가 이미 정본이다). 학생은 자기 계정에 연결된 학생이 없거나, 있어도
     * 그 id 가 경로의 {@code studentId} 와 다르면 {@code 403} 이다 — §3 도입부가 "본인 아닌 학생" 을
     * {@code 403} 으로 못박아, 여기서는 {@code 404} 로 존재 여부를 흘리지 않는다. <b>본인이어도 퇴원했으면
     * {@code 404}</b> 다(BR-212) — 퇴원 처리가 학생 본인 계정을 건드리지 않아, 판정이 없으면 명단에서 빠진
     * 학생이 실시간 버스 위치를 계속 받는다.
     */
    public Student resolve(AuthUser requester, Long studentId) {
        if (requester.role() == Role.STUDENT) {
            return resolveSelf(requester, studentId);
        }
        if (requester.role() == Role.PARENT) {
            return linkedChildLookup.linkedChild(requester, studentId);
        }
        throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    private Student resolveSelf(AuthUser requester, Long studentId) {
        Student self = studentRepository.findByAccountId(requester.accountId())
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
        if (!self.getId().equals(studentId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (self.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.STUDENT_NOT_FOUND);
        }
        return self;
    }
}
