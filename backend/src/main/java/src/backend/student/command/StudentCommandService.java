package src.backend.student.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.global.security.access.AcademyScope;
import src.backend.student.dto.StudentRegisterRequest;
import src.backend.student.dto.StudentUpdateRequest;
import src.backend.student.dto.StudentWithdrawalResponse;
import src.backend.student.entity.Gender;
import src.backend.student.entity.Student;
import src.backend.student.entity.StudentProfile;
import src.backend.student.photo.StudentPhotoWriter;
import src.backend.student.photo.spec.StudentPhoto;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 관계자 웹의 학생 등록 · 수정 · 퇴원(STU-02~04 · 07 · 08, API_SPEC §5.11).
 *
 * <p>세 경로 모두 <b>같은 저장소 조회</b>({@code findByIdAndAcademyIdAndDeletedAtIsNull})로 대상을
 * 꺼낸다 — 학원 조건과 퇴원 여부가 쿼리에 붙어 있어, "남의 학원 학생" 과 "없는 학생" 이 같은 빈 결과가
 * 되고 그대로 {@code 404 STUDENT_NOT_FOUND} 가 된다(§5.11). {@code 403} 이면 존재 여부가 새어 나간다.
 *
 * <p>사진 파일은 {@link StudentPhotoWriter} 를 거쳐 트랜잭션 안에서 저장된다(STU-02·03) — 저장에
 * 실패하면 예외가 그대로 올라가 학생 행이 남지 않고, 행이 롤백되면 저장했던 파일이 지워진다.
 *
 * <p>{@code guardian_student} 를 함께 만지는 것은 퇴원 하나뿐이다(Ruling 172) — 연결을 <b>만드는</b>
 * 경로는 자녀 연결(P-02)이 소유하고 여기서 열지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class StudentCommandService {

    private final StudentRepository studentRepository;

    private final GuardianStudentRepository guardianStudentRepository;

    private final StudentPhotoWriter studentPhotoWriter;

    private final Clock clock;

    /**
     * 학생을 등록한다(STU-02) — 소속 학원은 토큰이 정한다(§1.5).
     *
     * @param photo 올라온 사진. {@code null} 이면 사진 없이 등록되며 그것이 정상이다(§5.11 선택 필드)
     * @return 등록된 학생의 식별자 — 응답 조립은 조회 쪽이 맡는다(§1.9 "변경 후 자원 상태를 반환")
     */
    public Long register(AuthUser requester, StudentRegisterRequest request, StudentPhoto photo) {
        StudentProfile profile = new StudentProfile(request.name(), request.studentPhone(),
                studentPhotoWriter.store(photo), parseGender(request.gender()), request.birthDate(),
                request.grade(),
                request.className(), request.seatNo(), request.note(), request.canGoAlone());
        return studentRepository.save(Student.register(academyOf(requester), profile)).getId();
    }

    /**
     * 학생 정보를 고친다(STU-03 · 07 · 08) — 보낸 항목만 반영된다.
     *
     * <p>사진을 새로 올리면 옛 파일은 커밋 뒤에 지워진다 — 안 지우면 교체할 때마다 아무도 가리키지
     * 않는 파일이 디스크에 쌓인다.
     */
    public Long update(AuthUser requester, Long studentId, StudentUpdateRequest request,
            StudentPhoto photo) {
        Student student = find(requester, studentId);
        student.update(new StudentProfile(requirePresent(request.name()), request.studentPhone(),
                studentPhotoWriter.replace(student.getPhotoUrl(), photo), parseGender(request.gender()),
                request.birthDate(), request.grade(),
                request.className(), request.seatNo(), request.note(), request.canGoAlone()));
        return student.getId();
    }

    /**
     * 퇴원 처리한다(STU-04) — 학생에 {@code deleted_at} 을 채우는 soft delete 이고, <b>보호자 연결도
     * 함께 해제</b>한다(ERD §7.1 · UF-P-01 · Ruling 172).
     *
     * <p>이미 퇴원한 학생은 조회 조건에서 빠져 {@code 404} 가 된다 — 두 번째 요청이 성공하면
     * 퇴원 시각이 뒤로 밀려, 언제 명단에서 빠졌는지가 마지막 클릭 시각으로 덮인다.
     *
     * <p><b>연결 해제를 여기서 하는 이유</b> — 학부모 앱의 접근 범위 판정은 {@code unlinked_at IS NULL}
     * 을 보는데(§1.5), 학생 쪽만 지우고 연결을 남기면 <b>퇴원한 자녀가 옛 보호자의 목록에 계속
     * 남는다.</b> 두 값이 같은 사건("이 학생은 더 이상 이 학원의 학생이 아니다")을 가리키므로 같은
     * 트랜잭션에서 함께 움직인다.
     */
    public StudentWithdrawalResponse withdraw(AuthUser requester, Long studentId) {
        Student student = find(requester, studentId);
        OffsetDateTime at = OffsetDateTime.now(clock);
        student.withdraw(at);
        unlinkGuardians(student, at);
        return StudentWithdrawalResponse.from(student);
    }

    /**
     * 그 학생의 살아 있는 보호자 연결을 전부 해제한다 — <b>행은 지우지 않는다</b>(과거 이력 보존).
     *
     * <p>대상을 학생으로 좁히는 것이 요점이다. 보호자로 좁히면 형제 중 하나가 퇴원할 때 <b>나머지
     * 자녀까지</b> 앱에서 사라진다.
     */
    private void unlinkGuardians(Student student, OffsetDateTime at) {
        guardianStudentRepository.findActiveLinksOfStudent(student.getId(), student.getAcademyId())
                .forEach(link -> link.unlink(at));
    }

    private Student find(AuthUser requester, Long studentId) {
        return studentRepository.findByIdAndAcademyIdAndDeletedAtIsNull(studentId, academyOf(requester))
                .orElseThrow(() -> new BusinessException(ErrorCode.STUDENT_NOT_FOUND));
    }

    /**
     * 요청 주체의 소속 학원 — 학원을 특정할 수 없으면 거부한다.
     *
     * <p>빈 결과는 메인 관리자가 학원을 지정하지 않은 경우 하나뿐이고(ARCHITECTURE §6.2), 이 경로는
     * 관계자 웹이라 "전 학원 학생 등록" 이라는 동작 자체가 부재하다.
     */
    private Long academyOf(AuthUser requester) {
        return AcademyScope.resolveListScope(requester, null)
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
    }

    /**
     * 필수 항목은 <b>보내지 않는 것</b>만 허용하고 빈 문자열은 거부한다 — 공백만 남기면 이름 없는
     * 학생이 명단에 남는데 저장은 조용히 성공한다.
     */
    private String requirePresent(String value) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return value;
    }

    /** 값을 주지 않으면 {@code null}(= 바꾸지 않음)이고, 사양에 없는 값은 조용히 무시하지 않고 422 로 거부한다. */
    private Gender parseGender(String gender) {
        if (gender == null || gender.isBlank()) {
            return null;
        }
        try {
            return Gender.valueOf(gender.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }
}
