package src.backend.student.command;

import java.time.OffsetDateTime;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.persistence.ConstraintViolations;
import src.backend.student.entity.Guardian;
import src.backend.student.entity.GuardianStudent;
import src.backend.student.entity.Student;
import src.backend.student.repository.GuardianStudentRepository;

/**
 * 보호자 · 자녀 연결 행(S-05 · P-02, §3.4)을 남긴다 — 중복 연결을 선검사와 DB 거부 양쪽에서 같은 {@code 409 ALREADY_LINKED} 로 옮긴다.
 * {@link ChildLinkCommandService#completeLink} 의 트랜잭션 안에서 불리는 평범한 빈이라 자기 트랜잭션을 열지 않는다.
 */
@Component
@RequiredArgsConstructor
class GuardianLinkWriter {

    /**
     * 같은 자녀 중복 연결을 막는 제약 이름({@code V1__init_schema.sql}).
     *
     * <p>이름으로 가려 번역하는 이유는 이 저장이 {@code guardian_student} 의 FK 두 개
     * ({@code fk_guardian_student_guardian}·{@code fk_guardian_student_student})도 함께 지나기
     * 때문이다. 제약을 가리지 않고 {@code DataIntegrityViolationException} 을 통째로 409 로 옮기면
     * 사라진 보호자·학생을 가리킨 저장까지 "이미 연결된 대상입니다" 로 응답해 원인을 감춘다
     * ({@code BusCommandService} 가 호차 제약에 같은 형태를 쓴다).
     */
    private static final String GUARDIAN_STUDENT_UNIQUE_CONSTRAINT = "uk_guardian_student";

    private final GuardianStudentRepository guardianStudentRepository;

    /**
     * 연결 행을 남긴다 — 중복은 선검사에서든 DB 거부에서든 같은 {@code 409 ALREADY_LINKED} 다
     * (Ruling 164 의 요구가 Ruling 173 으로 이 자리에도 걸렸다).
     *
     * <p><b>{@link #assertNotLinked} 만으로는 부족하다.</b> 요청을 여러 번 보내면 살아 있는 코드가
     * 둘 이상 생기고, 두 창에서 각각 넣으면 두 트랜잭션이 서로의 미커밋 INSERT 를 보지 못한 채 둘 다
     * 선검사를 지난다. 그 뒤 {@code uk_guardian_student} 가 하나를 거부하는데, 그것을 옮기지 않으면
     * 사용자에게 {@code 500} 이 나가 "서버가 고장났다" 와 "이미 연결됐다" 가 구별되지 않는다.
     *
     * <p><b>{@link jakarta.persistence.EntityManager} 가 아니라 저장소의 flush 를 부른다</b> — 예외
     * 번역({@code DataIntegrityViolationException})은 {@code @Repository} 빈을 거칠 때만 붙어,
     * {@code EntityManager} 를 직접 부르면 Hibernate 예외가 이 {@code catch} 를 그대로 지나친다.
     */
    void save(Guardian guardian, Student student, OffsetDateTime now) {
        assertNotLinked(guardian, student.getId());
        try {
            guardianStudentRepository.save(GuardianStudent.uponLink(guardian.getAcademyId(), guardian.getId(), student.getId(), now));
            guardianStudentRepository.flush();
        } catch (DataIntegrityViolationException e) {
            if (isDuplicateLink(e)) {
                throw new BusinessException(ErrorCode.ALREADY_LINKED);
            }
            throw e;
        }
    }

    private boolean isDuplicateLink(DataIntegrityViolationException e) {
        return ConstraintViolations.isViolationOf(e, GUARDIAN_STUDENT_UNIQUE_CONSTRAINT);
    }

    /**
     * 같은 자녀 재연결을 막는다 — {@code 409 ALREADY_LINKED}(§8.5).
     *
     * <p>해지된 연결({@code unlinked_at})도 <b>연결된 것으로 센다.</b> 유일성을 강제하는 것은
     * {@code uk_guardian_student(guardian_id, student_id)} 이고 그 제약에 해지 여부가 부재하다 —
     * 해지분을 "없는 것" 으로 세면 재연결이 검사를 지나 DB 위반으로 떨어져 {@code 500} 이 된다.
     */
    private void assertNotLinked(Guardian guardian, Long studentId) {
        if (guardianStudentRepository.existsByGuardianIdAndStudentId(guardian.getId(), studentId)) {
            throw new BusinessException(ErrorCode.ALREADY_LINKED);
        }
    }
}
