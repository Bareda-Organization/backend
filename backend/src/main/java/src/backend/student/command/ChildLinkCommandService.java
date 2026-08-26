package src.backend.student.command;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.student.access.GuardianChildAccess;
import src.backend.student.dto.ChildLinkedResponse;
import src.backend.student.dto.LinkCodeIssueResponse;
import src.backend.student.dto.LinkRequestCreatedResponse;
import src.backend.student.entity.Guardian;
import src.backend.student.entity.GuardianStudent;
import src.backend.student.entity.LinkCode;
import src.backend.student.entity.LinkRequest;
import src.backend.student.entity.LinkRequestStatus;
import src.backend.student.entity.Student;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.LinkCodeRepository;
import src.backend.student.repository.LinkRequestRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 자녀 연결 3단계(P-02 · S-05, API_SPEC §3.2~§3.4) — 요청(보호자) → 코드 생성(학생) → 코드 입력(보호자).
 *
 * <p><b>주체가 번갈아 바뀌는데도 한 클래스에 둔다.</b> 세 단계가 공유하는 것은 호출자가 아니라
 * {@code link_request} · {@code link_code} 의 불변식(요청 없이는 코드가 없고, 코드 없이는 연결이
 * 없으며, 코드는 한 번만 쓰인다)이다. 주체로 가르면 그 불변식이 두 파일에 흩어져 한쪽만 고쳐진다.
 *
 * <p>거부 3종(만료 · 불일치 · 재사용)이 <b>같은 {@code 403 LINK_CODE_INVALID}</b> 로 합류하는 자리가
 * {@link #completeLink} 하나다 — 판정을 갈라 두면 "이 코드는 실재하는데 만료됐다" 가 응답에서 새어
 * 나간다(§3.4).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ChildLinkCommandService {

    /**
     * 연결 요청의 유효 기간(§3.2 {@code expires_at}).
     *
     * <p>사양이 값을 규정하지 않아 이 서비스가 정한다(횡단 규칙 10 — 정책 상수는 코드 상수). 30분은
     * 시드({@code V2__seed_data.sql} 의 {@code link_request})가 전제한 폭과 같은 값이며, 학부모가
     * 자녀에게 연락해 앱을 열게 하는 데 걸리는 시간을 담되 방치된 요청이 오래 남지 않는 선이다.
     */
    private static final long REQUEST_VALIDITY_MINUTES = 30;

    /**
     * 인증 코드의 유효 기간(§3.3 {@code expires_at}) — 요청보다 짧다.
     *
     * <p>코드는 <b>화면에 떠 있는 자격 증명</b>이라 노출 창이 곧 위험 구간이다. 요청과 같은 폭을 주면
     * 학생이 화면을 켜 둔 채 자리를 비운 30분 동안 옆사람이 그대로 읽어 갈 수 있다.
     */
    private static final long CODE_VALIDITY_MINUTES = 10;

    /** 코드 자릿수 — {@code link_code.code} 가 {@code varchar(10)} 이고 학생이 불러 주는 값이다. */
    private static final int CODE_LENGTH = 6;

    private final GuardianChildAccess guardianChildAccess;

    private final StudentRepository studentRepository;

    private final GuardianStudentRepository guardianStudentRepository;

    private final LinkRequestRepository linkRequestRepository;

    private final LinkCodeRepository linkCodeRepository;

    private final Clock clock;

    /**
     * 코드가 추측 가능하면 6자리를 훑어 남의 자녀를 가져갈 수 있으므로 {@link SecureRandom} 이다 —
     * {@code java.util.Random} 은 시드에서 수열 전체가 결정된다({@code TemporaryPasswordGenerator} 와 같은 근거).
     */
    private final SecureRandom random = new SecureRandom();

    /**
     * ① 학부모가 자녀 로그인 아이디로 연결을 신청한다(P-02, §3.2).
     *
     * <p>이미 연결된 자녀는 {@code 409 ALREADY_LINKED} 로 여기서 막는다 — 코드까지 만들게 두면 학생이
     * 헛되이 코드를 발급하고 마지막 단계에서야 거부된다.
     */
    public LinkRequestCreatedResponse requestLink(AuthUser requester, String studentLoginId) {
        Guardian guardian = guardianChildAccess.requireGuardian(requester);
        Student student = studentRepository.findByLoginIdAndAcademyId(studentLoginId, guardian.getAcademyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.STUDENT_NOT_FOUND));
        assertNotLinked(guardian, student.getId());

        OffsetDateTime now = OffsetDateTime.now(clock);
        LinkRequest saved = linkRequestRepository.save(LinkRequest.uponRequest(
                guardian.getId(), student.getId(), now, now.plusMinutes(REQUEST_VALIDITY_MINUTES)));
        return LinkRequestCreatedResponse.from(saved);
    }

    /**
     * ② 학생이 대기 중인 요청에 대해 인증 코드를 만든다(S-05, §3.3).
     *
     * <p>요청 본문이 부재하므로 <b>어느 요청에 붙일지는 서버가 정한다</b> — 대기 중이고 만료되지 않은
     * 것 중 가장 최근 1건이다. 학생 레코드가 없는 계정(기사·학부모 등)은 {@code 403 FORBIDDEN} 이다.
     */
    public LinkCodeIssueResponse issueCode(AuthUser requester) {
        Student student = studentRepository.findByAccountId(requester.accountId())
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
        OffsetDateTime now = OffsetDateTime.now(clock);
        LinkRequest pending = latestPendingRequest(student, now);

        LinkCode saved = linkCodeRepository.save(LinkCode.forRequest(pending.getId(), generateCode(),
                now.plusMinutes(CODE_VALIDITY_MINUTES), now));
        return LinkCodeIssueResponse.from(saved);
    }

    /**
     * ③ 학부모가 코드를 입력하면 서버가 대조해 연결을 성립시킨다(P-02, §3.4).
     *
     * <p>판정 순서가 이 메서드의 핵심이다 — <b>코드 유효성이 먼저</b>고 연결 여부가 나중이다. 뒤집으면
     * 이미 연결된 자녀에 대해 "코드는 맞다" 가 {@code 409} 로 드러나, 유출된 코드를 쥔 쪽이 그 값이
     * 실재하는지 확인할 수 있다.
     */
    public ChildLinkedResponse completeLink(AuthUser requester, String code) {
        Guardian guardian = guardianChildAccess.requireGuardian(requester);
        OffsetDateTime now = OffsetDateTime.now(clock);
        LinkCode linkCode = usableCode(guardian, code, now);
        LinkRequest linkRequest = linkRequestRepository.findById(linkCode.getLinkRequestId())
                .orElseThrow(() -> new BusinessException(ErrorCode.LINK_CODE_INVALID));

        Student student = studentRepository.findByIdAndAcademyIdAndDeletedAtIsNull(
                linkRequest.getStudentId(), guardian.getAcademyId())
                .orElseThrow(() -> new BusinessException(ErrorCode.STUDENT_NOT_FOUND));
        assertNotLinked(guardian, student.getId());

        linkCode.markUsed(now);
        linkRequest.complete();
        guardianStudentRepository.save(GuardianStudent.uponLink(guardian.getId(), student.getId(), now));
        return ChildLinkedResponse.from(student);
    }

    /**
     * 대기 중이고 만료되지 않은 요청 1건 — 없으면 {@code 404 LINK_REQUEST_NOT_FOUND}.
     *
     * <p>⚠ {@code §3.3} 은 이 경우의 응답을 규정하지 않는다. {@code link_code.link_request_id} 가
     * FK NN 이라 요청 없이 코드를 만들 수단 자체가 부재해 사양의 빈칸을 채운 것이고, 조율자 판정
     * 대상으로 신고했다({@code ErrorCode#LINK_REQUEST_NOT_FOUND} 주석 참조).
     */
    private LinkRequest latestPendingRequest(Student student, OffsetDateTime now) {
        List<LinkRequest> pending = linkRequestRepository.findPendingForStudent(
                student.getId(), student.getAcademyId(), LinkRequestStatus.PENDING, now, Limit.of(1));
        if (pending.isEmpty()) {
            throw new BusinessException(ErrorCode.LINK_REQUEST_NOT_FOUND);
        }
        return pending.getFirst();
    }

    /**
     * 그 보호자에게 발급된 코드 중 <b>지금 쓸 수 있는</b> 것 — 없으면 {@code 403 LINK_CODE_INVALID}.
     *
     * <p>불일치(후보 0건) · 만료 · 재사용이 여기서 같은 결과로 합쳐진다. 요청을 여러 번 보내 살아
     * 있는 코드가 둘 이상일 수 있으므로 후보를 훑어 쓸 수 있는 첫 건을 고른다.
     */
    private LinkCode usableCode(Guardian guardian, String code, OffsetDateTime now) {
        return linkCodeRepository.findByCodeForGuardian(code, guardian.getId(), guardian.getAcademyId())
                .stream()
                .filter(candidate -> candidate.isUsable(now))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.LINK_CODE_INVALID));
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

    private String generateCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(random.nextInt(10));
        }
        return code.toString();
    }
}
