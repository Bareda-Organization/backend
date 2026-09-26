package src.backend.student.command;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.persistence.ConstraintViolations;
import src.backend.global.security.AuthUser;
import src.backend.student.access.GuardianChildAccess;
import src.backend.student.dto.ChildLinkedResponse;
import src.backend.student.dto.LinkCodeIssueResponse;
import src.backend.student.entity.Guardian;
import src.backend.student.entity.GuardianStudent;
import src.backend.student.entity.LinkCode;
import src.backend.student.entity.Student;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.LinkCodeRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 자녀 연결 2단계(P-02 · S-05, API_SPEC §3.3~§3.4) — 코드 생성(학생) → 코드 입력(학부모).
 *
 * <p><b>Ruling 324 — 연결 요청 단계를 없앴다.</b> 이전에는 학부모의 요청(§3.2)이 있어야 학생이 코드를
 * 만들 수 있었으나, 지금은 학생이 <b>언제든</b> 코드를 만든다. 가입 승인은 계정 활성화만 하고(§5.2),
 * 자녀 연결은 각 앱에서 이 2단계로 따로 진행한다(2026-09-22 사용자 확정).
 *
 * <p><b>주체가 번갈아 바뀌는데도 한 클래스에 둔다.</b> 두 단계가 공유하는 것은 호출자가 아니라
 * {@code link_code} 의 불변식(코드는 한 번만 쓰이고 만료가 있다)이다. 주체로 가르면 그 불변식이 두
 * 파일에 흩어져 한쪽만 고쳐진다.
 *
 * <p>거부(만료 · 불일치 · 재사용 · 시도 상한 · 퇴원)가 <b>같은 {@code 403 LINK_CODE_INVALID}</b> 로
 * 합류하는 자리가 {@link #completeLink} 의 빈 결과 하나다 — 판정을 갈라 두면 "이 코드는 실재하는데
 * 만료됐다" 가 응답에서 새어 나간다(§3.4).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ChildLinkCommandService {

    /**
     * 인증 코드의 유효 기간(§3.3 {@code expires_at}).
     *
     * <p>코드는 <b>화면에 떠 있는 자격 증명</b>이라 노출 창이 곧 위험 구간이다. 폭을 넓게 주면
     * 학생이 화면을 켜 둔 채 자리를 비운 동안 옆사람이 그대로 읽어 갈 수 있다.
     */
    private static final long CODE_VALIDITY_MINUTES = 10;

    /** 코드 자릿수 — {@code link_code.code} 가 {@code varchar(10)} 이고 학생이 불러 주는 값이다. */
    private static final int CODE_LENGTH = 6;

    /**
     * 보호자 한 명이 {@link #CODE_VALIDITY_MINUTES} 창 안에 코드를 넣을 수 있는 횟수(BR-024).
     *
     * <p>창을 코드 수명과 같게 두어, 코드 하나가 살아 있는 동안 한 보호자의 추측 기회가 5번으로 묶인다.
     * 5 는 로그인 차단(C-11)·복구 코드 대조 상한과 같은 값이다.
     */
    private static final int MAX_ATTEMPTS_PER_WINDOW = 5;

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

    private final GuardianChildAccess guardianChildAccess;

    private final StudentRepository studentRepository;

    private final GuardianRepository guardianRepository;

    private final GuardianStudentRepository guardianStudentRepository;

    private final LinkCodeRepository linkCodeRepository;

    private final Clock clock;

    /**
     * 코드가 추측 가능하면 6자리를 훑어 남의 자녀를 가져갈 수 있으므로 {@link SecureRandom} 이다 —
     * {@code java.util.Random} 은 시드에서 수열 전체가 결정된다({@code TemporaryPasswordGenerator} 와 같은 근거).
     */
    private final SecureRandom random = new SecureRandom();

    /**
     * ① 학생이 인증 코드를 만든다(S-05, §3.3) — 선행 조건이 없다(Ruling 324).
     *
     * <p>학생 레코드가 없는 계정(기사·학부모 등)과 퇴원한 학생은 {@code 403 FORBIDDEN} 이다 — 퇴원생의
     * 코드는 어차피 연결되지 않는다(BR-122).
     */
    public LinkCodeIssueResponse issueCode(AuthUser requester) {
        Student student = studentRepository.findByAccountId(requester.accountId())
                .filter(found -> found.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
        OffsetDateTime now = OffsetDateTime.now(clock);

        LinkCode saved = linkCodeRepository.save(LinkCode.forStudent(student.getId(), generateCode(),
                now.plusMinutes(CODE_VALIDITY_MINUTES), now));
        return LinkCodeIssueResponse.from(saved);
    }

    /**
     * ② 학부모가 코드를 입력하면 서버가 대조해 연결을 성립시킨다(P-02, §3.4).
     *
     * <p>판정 순서가 이 메서드의 핵심이다 — <b>코드 유효성이 먼저</b>고 연결 여부가 나중이다. 뒤집으면
     * 이미 연결된 자녀에 대해 "코드는 맞다" 가 {@code 409} 로 드러나, 유출된 코드를 쥔 쪽이 그 값이
     * 실재하는지 확인할 수 있다.
     *
     * <p><b>거부를 예외가 아니라 빈 결과로 돌려준다</b> — 예외를 던지면 트랜잭션이 롤백되며 방금 센 시도
     * 횟수까지 사라져 상한이 무의미해진다(BR-024). {@code 403 LINK_CODE_INVALID} 는 호출부가 커밋 뒤에 던진다.
     *
     * @return 연결 결과, 코드를 쓸 수 없으면 빈 값
     */
    public Optional<ChildLinkedResponse> completeLink(AuthUser requester, String code) {
        Guardian guardian = guardianChildAccess.requireGuardian(requester);
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (guardianRepository.consumeLinkAttempt(guardian.getId(), guardian.getAcademyId(), now,
                now.minusMinutes(CODE_VALIDITY_MINUTES), MAX_ATTEMPTS_PER_WINDOW) == 0) {
            return Optional.empty();
        }
        Optional<LinkCode> linkCode = usableCode(guardian, code, now);
        Optional<Student> student = linkCode.flatMap(found -> studentRepository
                .findByIdAndAcademyIdAndDeletedAtIsNull(found.getStudentId(), guardian.getAcademyId()));
        if (student.isEmpty()
                || linkCodeRepository.markUsedIfUnused(linkCode.get().getId(), guardian.getAcademyId(), now) == 0) {
            return Optional.empty();
        }
        saveLink(guardian, student.get(), now);
        return Optional.of(ChildLinkedResponse.from(student.get()));
    }

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
    private void saveLink(Guardian guardian, Student student, OffsetDateTime now) {
        assertNotLinked(guardian, student.getId());
        try {
            guardianStudentRepository.save(GuardianStudent.uponLink(guardian.getId(), student.getId(), now));
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
     * 그 학원 안에서 <b>지금 쓸 수 있는</b> 코드 — 정확히 1건일 때만 돌려준다.
     *
     * <p>불일치(후보 0건) · 만료 · 재사용이 여기서 같은 결과로 합쳐진다. 발급은 학원 안 중복을 보지 않아
     * 6자리 난수가 겹칠 수 있고, 쓸 수 있는 후보가 둘이면 어느 쪽을 골라도 <b>다른 집 자녀</b>를 연결할 수
     * 있어 거부한다(BR-085). 학생이 다시 발급하면 풀린다.
     */
    private Optional<LinkCode> usableCode(Guardian guardian, String code, OffsetDateTime now) {
        List<LinkCode> usable = linkCodeRepository.findByCodeForAcademy(code, guardian.getAcademyId())
                .stream()
                .filter(candidate -> candidate.isUsable(now))
                .toList();
        return usable.size() == 1 ? Optional.of(usable.get(0)) : Optional.empty();
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
