package src.backend.student.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.audit.entity.AuditAction;
import src.backend.audit.entity.AuditLog;
import src.backend.audit.repository.AuditLogRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.request.ClientIp;
import src.backend.global.request.Patch;
import src.backend.global.security.AuthUser;
import src.backend.global.security.access.AcademyScope;
import src.backend.student.dto.StudentRegisterRequest;
import src.backend.student.dto.StudentUpdateRequest;
import src.backend.student.dto.StudentWithdrawalResponse;
import src.backend.student.entity.Gender;
import src.backend.student.entity.Guardian;
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

    private final AuditLogRepository auditLogRepository;

    private final AccountRepository accountRepository;

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
                request.className(), request.note(), request.canGoAlone());
        return studentRepository.save(Student.register(academyOf(requester), profile)).getId();
    }

    /**
     * 강제 추가(RTE-06, API_SPEC §5.7) 중 신규 학생 직접 입력을 등록한다(BR-095, {@code run} 소유
     * {@code ForcedAdditionStore#stage} 전용) — {@link #register} 와 별도 진입점을 두는 이유는 이
     * 경로가 관계자 웹 세션({@link AuthUser})이 아니라 배치 컨텍스트(회차·강제 추가 담당자)에서 오기
     * 때문이다. §5.7 은 이름만 받으므로 사진·학년·반 등 나머지 {@link StudentProfile} 필드는 비운다 —
     * 전체 등록(STU-02)의 입력 형태까지 맞출 필요는 없다(YAGNI), {@code student} 모듈이 자기 테이블의
     * 유일한 쓰기 지점이라는 원칙만 지키면 된다.
     */
    public Long registerMinimal(Long academyId, String name) {
        StudentProfile profile = new StudentProfile(name, null, null, null, null, null, null, null, null);
        return studentRepository.save(Student.register(academyId, profile)).getId();
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
        student.update(new StudentProfile(Patch.required(request.name(), student.getName()),
                Patch.optional(request.studentPhone(), student.getStudentPhone()),
                studentPhotoWriter.replace(student.getPhotoUrl(), photo), genderOf(request, student),
                Patch.optional(request.birthDate(), student.getBirthDate()),
                Patch.optional(request.grade(), student.getGrade()),
                Patch.optional(request.className(), student.getClassName()),
                Patch.optional(request.note(), student.getNote()),
                Patch.required(request.canGoAlone(), student.isCanGoAlone())));
        if (request.guardians() != null) {
            changeGuardianPhones(student, request.guardians());
        }
        List<String> l3Fields = new ArrayList<>();
        if (photo != null) {
            l3Fields.add("photo_url");
        }
        if (request.note() != null) {
            l3Fields.add("note");
        }
        if (request.guardians() != null) {
            l3Fields.add("guardians");
        }
        if (!l3Fields.isEmpty()) {
            recordChange(requester, student, AuditAction.UPDATE, Map.of("fields", l3Fields));
        }
        return student.getId();
    }

    /**
     * 보호자 연락처를 고친다(Ruling 326) — <b>고치기 전에 전부 확인한다.</b> 이 학생과 연결되지 않은 보호자가 하나라도
     * 섞이면 아무것도 바꾸지 않은 채 {@code 422} 다. 확인하지 않으면 학생 id 하나로 학원의 아무 보호자 번호나 바꾼다.
     */
    private void changeGuardianPhones(Student student, List<StudentUpdateRequest.GuardianPhoneChange> changes) {
        Map<String, Guardian> linked = guardianStudentRepository
                .findLinkedGuardians(student.getAcademyId(), student.getId()).stream()
                .collect(Collectors.toMap(guardian -> String.valueOf(guardian.getId()), guardian -> guardian));
        if (!changes.stream().allMatch(change -> linked.containsKey(change.guardianId()))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "이 학생과 연결되지 않은 보호자다");
        }
        changes.forEach(change -> linked.get(change.guardianId()).changePhone(change.phone()));
    }

    /**
     * 퇴원 처리한다(STU-04) — 학생에 {@code deleted_at} 을 채우는 soft delete 이고, <b>보호자 연결도
     * 함께 해제</b>한다(ERD §7.1 · UF-P-01 · Ruling 172).
     *
     * <p>이미 퇴원한 학생은 조회 조건에서 빠져 {@code 404} 가 된다 — 두 번째 요청이 성공하면
     * 퇴원 시각이 뒤로 밀려, 언제 명단에서 빠졌는지가 마지막 클릭 시각으로 덮인다.
     *
     * <p><b>연결 해제를 여기서 하는 이유</b> — 학부모 앱의 접근 범위 판정은 {@code unlinked_at IS NULL}
     * 을 보는데(API_SPEC §1.5), 학생 쪽만 지우고 연결을 남기면 <b>퇴원한 자녀가 옛 보호자의 목록에 계속
     * 남는다.</b> 두 값이 같은 사건("이 학생은 더 이상 이 학원의 학생이 아니다")을 가리키므로 같은
     * 트랜잭션에서 함께 움직인다.
     */
    public StudentWithdrawalResponse withdraw(AuthUser requester, Long studentId) {
        Student student = find(requester, studentId);
        OffsetDateTime at = OffsetDateTime.now(clock);
        student.withdraw(at);
        unlinkGuardians(student, at);
        recordChange(requester, student, AuditAction.DELETE, Map.of());
        return StudentWithdrawalResponse.from(student);
    }

    /**
     * L3 수정·퇴원을 감사 1행으로 남긴다(Ruling 333 · SYS-01) — 변경과 <b>같은 트랜잭션</b>이다. 강제 확정·차단 해제와
     * 같은 근거로, 감사가 빠진 변경은 누가 보호자 번호를 바꿨는지 되짚을 근거가 없어 변경 자체를 남기지 않는다.
     */
    private void recordChange(AuthUser requester, Student student, AuditAction action, Map<String, Object> detail) {
        String actorLoginId = accountRepository.findById(requester.accountId()).map(Account::getLoginId).orElse(null);
        auditLogRepository.save(AuditLog.forDataAccessChange(action, student.getAcademyId(), requester.accountId(),
                actorLoginId, "student", student.getId(), detail, ClientIp.ofCurrentRequest(),
                OffsetDateTime.now(clock)));
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

    /** 성별은 문자열로 받아 {@link Gender} 로 옮기므로 {@link Patch#optional} 을 못 쓴다 — 키 없음·지움·값을 여기서 가른다. */
    private Gender genderOf(StudentUpdateRequest request, Student student) {
        if (request.gender() == null) {
            return student.getGender();
        }
        return parseGender(request.gender().value());
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
