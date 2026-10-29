package src.backend.student.access;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.global.security.access.StudentChannelAccess;
import src.backend.student.repository.StudentRepository;

/**
 * 학생 채널 구독 인가 — 학생 본인(퇴원하지 않은) 또는 연결된 보호자만 통과시킨다. 보호자가 아닌 역할(기사 · 동승자 · 관계자 · 메인 관리자)은 보호자 행을 못 찾아
 * {@link GuardianChildAccess#assertLinkedChild} 에서 자연히 {@code FORBIDDEN} 이다 — 별도 역할 분기를 두지 않는다.
 */
@Component
@RequiredArgsConstructor
class LinkedFollowerStudentChannelAccess implements StudentChannelAccess {

    private final GuardianChildAccess guardianChildAccess;

    private final StudentRepository studentRepository;

    @Override
    public void assertMayFollowStudent(AuthUser subscriber, Long studentId) {
        if (subscriber.role() != Role.STUDENT) {
            guardianChildAccess.assertLinkedChild(subscriber, studentId);
            return;
        }
        boolean self = studentRepository.findByAccountId(subscriber.accountId())
                .filter(student -> student.getDeletedAt() == null)
                .map(student -> student.getId().equals(studentId))
                .orElse(false);
        if (!self) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }
}
