package src.backend.student.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.audit.entity.AuditAction;
import src.backend.audit.entity.AuditLog;
import src.backend.audit.service.AuditRecorder;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.request.ClientIp;
import src.backend.global.security.AuthUser;
import src.backend.global.security.access.AcademyScope;
import src.backend.global.websocket.AccountSessionsRevokeEvent;
import src.backend.student.dto.StudentRegisterRequest;
import src.backend.student.dto.StudentUpdateRequest;
import src.backend.student.dto.StudentWithdrawalResponse;
import src.backend.student.entity.Student;
import src.backend.student.entity.StudentProfile;
import src.backend.student.photo.StudentPhotoWriter;
import src.backend.student.photo.spec.StudentPhoto;
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

    /** {@code student.name} 의 컬럼 길이 — 요청 DTO 를 거치지 않는 최소 등록이 스스로 지킨다. */
    private static final int NAME_MAX_LENGTH = 50;

    private final StudentRepository studentRepository;

    private final StudentGuardianLinks guardianLinks;

    private final StudentPhotoWriter studentPhotoWriter;

    private final AuditRecorder auditRecorder;

    private final AccountRepository accountRepository;

    private final Clock clock;

    private final ApplicationEventPublisher eventPublisher;

    /**
     * 학생을 등록한다(STU-02) — 소속 학원은 토큰이 정한다(§1.5).
     *
     * @param photo 올라온 사진 — 트랜잭션 밖에서 이미 줄여 온 것이다(BR-322). {@code null} 이면 사진 없이 등록되며 그것이 정상이다(§5.11 선택 필드)
     * @return 등록된 학생의 식별자 — 응답 조립은 조회 쪽이 맡는다(§1.9 "변경 후 자원 상태를 반환")
     */
    public Long register(AuthUser requester, StudentRegisterRequest request, StudentPhoto photo) {
        StudentProfile profile = StudentProfiles.forRegistration(request, () -> studentPhotoWriter.store(photo));
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
        if (name == null || name.isBlank() || name.length() > NAME_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
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
        student.update(StudentProfiles.forUpdate(request, student,
                () -> studentPhotoWriter.replace(student.getPhotoUrl(), photo)));
        if (request.guardians() != null) {
            guardianLinks.changePhones(student, request.guardians());
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
     * 학생 사진을 지운다(Ruling 894 ③) — {@code photo_url} 을 비우고 저장 파일은 커밋 뒤 지운다. 사진이 이미 없으면 아무것도 하지
     * 않는다(멱등 · 감사도 남기지 않는다 — 바뀐 것이 없다). 감사는 사진 교체와 같은 L3 수정 1행이다.
     */
    public void removePhoto(AuthUser requester, Long studentId) {
        Student student = find(requester, studentId);
        if (student.getPhotoUrl() == null) {
            return;
        }
        studentPhotoWriter.remove(student.getPhotoUrl());
        student.removePhoto();
        recordChange(requester, student, AuditAction.UPDATE, Map.of("fields", List.of("photo_url")));
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
        Set<Long> affectedAccounts = guardianLinks.openChannelAccountsOf(student); // 연결을 끊기 전에 모은다 — 끊은 뒤에는 보호자를 찾을 수 없다
        guardianLinks.unlinkAll(student, at);
        eventPublisher.publishEvent(new AccountSessionsRevokeEvent(affectedAccounts)); // 커밋 직후 그 계정들의 열린 세션을 닫는다
        recordChange(requester, student, AuditAction.DELETE, Map.of());
        return StudentWithdrawalResponse.from(student);
    }

    /**
     * L3 수정·퇴원을 감사 1행으로 남긴다(Ruling 333 · SYS-01) — 변경과 <b>같은 트랜잭션</b>이다. 강제 확정·차단 해제와
     * 같은 근거로, 감사가 빠진 변경은 누가 보호자 번호를 바꿨는지 되짚을 근거가 없어 변경 자체를 남기지 않는다.
     */
    private void recordChange(AuthUser requester, Student student, AuditAction action, Map<String, Object> detail) {
        String actorLoginId = accountRepository.findById(requester.accountId()).map(Account::getLoginId).orElse(null);
        auditRecorder.record(AuditLog.forDataAccessChange(action, student.getAcademyId(), requester.accountId(),
                actorLoginId, "student", student.getId(), detail, ClientIp.ofCurrentRequest(),
                OffsetDateTime.now(clock)));
    }

    /** 행 잠금으로 꺼낸다 — 수정과 퇴원이 같은 행을 읽고 쓰는 사이에 끼면 나중에 커밋한 쪽이 앞선 변경(퇴원 시각 포함)을 덮어쓴다. */
    private Student find(AuthUser requester, Long studentId) {
        return studentRepository.lockActiveByIdAndAcademyId(studentId, academyOf(requester))
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

}
