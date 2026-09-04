package src.backend.student.access;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.student.entity.Guardian;
import src.backend.student.entity.Student;
import src.backend.student.repository.StudentRepository;

/**
 * 학부모 경로가 {@code {id}} 로 지목한 자녀를 <b>레코드로</b> 꺼낸다 — 판정은 하지 않고
 * {@link GuardianChildAccess} 에 맡긴다.
 *
 * <p>{@link GuardianChildAccess} 와 가른 축은 반환값이다. 저쪽은 "만질 수 있는가" 에 예·아니오로
 * 답하고, 이쪽은 그 뒤에 필요한 <b>학원 식별자와 학생 레코드</b>를 준다. 판정을 이 클래스로 옮기면
 * 목록 경로(§3.1)와 단건 경로가 서로 다른 판정을 갖게 되는데, 그것이 {@code ARCHITECTURE §6.1} 이
 * 지목한 사고 지점이다.
 *
 * <p>조회를 서비스마다 되풀이하지 않는 이유는 <b>거부 코드의 순서</b> 때문이다 — 연결이 없으면
 * {@code 403}, 연결은 있는데 퇴원했으면 {@code 404} 다(API_SPEC §3.7). 순서를 각자 정하면 같은 상황에 경로마다
 * 다른 코드가 나간다.
 */
@Component
@RequiredArgsConstructor
public class LinkedChildLookup {

    private final GuardianChildAccess guardianChildAccess;

    private final StudentRepository studentRepository;

    /**
     * 연결된 자녀를 꺼낸다 — 연결 부재는 {@code 403 FORBIDDEN}, 퇴원생은 {@code 404 STUDENT_NOT_FOUND}.
     *
     * <p>{@code 404} 가 죽은 가지가 아니다. 연결은 살아 있는데 관계자가 퇴원 처리한 학생
     * ({@code student.deleted_at})이 그 자리이고, 그 학생의 주소를 계속 고칠 수 있으면 명단에서 빠진
     * 학생이 노선 계산의 입력으로 되살아난다.
     */
    public Student linkedChild(AuthUser requester, Long studentId) {
        Guardian guardian = guardianChildAccess.requireGuardian(requester);
        guardianChildAccess.assertLinkedChild(requester, studentId);
        return studentRepository.findByIdAndAcademyIdAndDeletedAtIsNull(studentId, guardian.getAcademyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.STUDENT_NOT_FOUND));
    }
}
