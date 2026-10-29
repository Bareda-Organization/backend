package src.backend.student.command;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.student.dto.StudentUpdateRequest;
import src.backend.student.entity.Guardian;
import src.backend.student.entity.Student;
import src.backend.student.repository.GuardianAccountRecipient;
import src.backend.student.repository.GuardianStudentRepository;

/**
 * 학생 수정 · 퇴원이 만지는 보호자 쪽 단계 — 보호자 연락처 수정 · 연결 해제 · 열린 채널 계정 수집. {@link StudentCommandService} 의 트랜잭션 안에서 불리는
 * 평범한 빈이라 자기 트랜잭션을 열지 않는다.
 */
@Component
@RequiredArgsConstructor
class StudentGuardianLinks {

    private final GuardianStudentRepository guardianStudentRepository;

    /**
     * 보호자 연락처를 고친다(Ruling 326) — <b>고치기 전에 전부 확인한다.</b> 이 학생과 연결되지 않은 보호자가 하나라도
     * 섞이면 아무것도 바꾸지 않은 채 {@code 422} 다. 확인하지 않으면 학생 id 하나로 학원의 아무 보호자 번호나 바꾼다.
     */
    void changePhones(Student student, List<StudentUpdateRequest.GuardianPhoneChange> changes) {
        Map<String, Guardian> linked = guardianStudentRepository
                .findLinkedGuardians(student.getAcademyId(), student.getId()).stream()
                .collect(Collectors.toMap(guardian -> String.valueOf(guardian.getId()), guardian -> guardian));
        if (!changes.stream().allMatch(change -> linked.containsKey(change.guardianId()))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "이 학생과 연결되지 않은 보호자다");
        }
        changes.forEach(change -> linked.get(change.guardianId()).changePhone(change.phone()));
    }

    /**
     * 그 학생의 살아 있는 보호자 연결을 전부 해제한다 — <b>행은 지우지 않는다</b>(과거 이력 보존).
     *
     * <p>대상을 학생으로 좁히는 것이 요점이다. 보호자로 좁히면 형제 중 하나가 퇴원할 때 <b>나머지
     * 자녀까지</b> 앱에서 사라진다.
     */
    void unlinkAll(Student student, OffsetDateTime at) {
        guardianStudentRepository.findActiveLinksOfStudent(student.getId(), student.getAcademyId())
                .forEach(link -> link.unlink(at));
    }

    /** 이 학생의 채널을 구독할 수 있던 계정 — 살아 있는 보호자 연결의 계정과 학생 본인 계정. */
    Set<Long> openChannelAccountsOf(Student student) {
        Set<Long> accounts = guardianStudentRepository
                .findGuardianAccountsByAcademyId(student.getAcademyId(), List.of(student.getId())).stream()
                .map(GuardianAccountRecipient::getAccountId)
                .collect(Collectors.toCollection(HashSet::new));
        if (student.getAccountId() != null) {
            accounts.add(student.getAccountId());
        }
        return accounts;
    }
}
