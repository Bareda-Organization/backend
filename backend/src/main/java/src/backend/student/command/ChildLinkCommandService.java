package src.backend.student.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.student.access.GuardianChildAccess;
import src.backend.student.dto.ChildLinkedResponse;
import src.backend.student.dto.LinkCodeIssueResponse;
import src.backend.student.entity.Guardian;
import src.backend.student.entity.LinkCode;
import src.backend.student.entity.Student;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.LinkCodeRepository;
import src.backend.student.repository.StudentRepository;

/**
 * 자녀 연결 2단계(P-02 · S-05, API_SPEC §3.3~§3.4) — 코드 생성(학생) → 코드 입력(학부모).
 *
 * <p><b>Ruling 324 — 연결 요청 단계를 없앴다.</b> 학부모의 요청(§3.2) 없이 학생이 <b>언제든</b> 코드를 만든다.
 * 가입 승인은 계정 활성화만 하고(§5.2), 자녀 연결은 각 앱에서 이 2단계로 따로 진행한다.
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

    /**
     * 보호자 한 명이 {@link #CODE_VALIDITY_MINUTES} 창 안에 코드를 넣을 수 있는 횟수(BR-024).
     *
     * <p>창을 코드 수명과 같게 두어, 코드 하나가 살아 있는 동안 한 보호자의 추측 기회가 5번으로 묶인다.
     * 5 는 로그인 차단(C-11)·복구 코드 대조 상한과 같은 값이다.
     */
    private static final int MAX_ATTEMPTS_PER_WINDOW = 5;

    private final GuardianChildAccess guardianChildAccess;

    private final StudentRepository studentRepository;

    private final GuardianRepository guardianRepository;

    private final GuardianLinkWriter linkWriter;

    private final LinkCodeRepository linkCodeRepository;

    private final LinkCodeGenerator codeGenerator;

    private final Clock clock;

    /**
     * ① 학생이 인증 코드를 만든다(S-05, §3.3) — 선행 조건이 없다(Ruling 324).
     *
     * <p>학생 레코드가 없는 계정(기사·학부모 등)과 퇴원한 학생은 {@code 403 FORBIDDEN} 이다 — 퇴원생의
     * 코드는 어차피 연결되지 않는다(BR-122).
     *
     * <p>다시 발급하면 <b>이전 코드는 그 자리에서 만료</b>된다 — 화면에는 가장 나중 코드가 떠 있다. 새 코드는 학원 안에서
     * 살아 있는 다른 코드와 겹치지 않게 뽑아, 입력 쪽 "후보가 둘이면 거부"({@link #usableCode})가 정상 사용에서
     * 발동하지 않게 한다(BR-215).
     */
    public LinkCodeIssueResponse issueCode(AuthUser requester) {
        Student student = studentRepository.findByAccountId(requester.accountId())
                .filter(found -> found.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
        OffsetDateTime now = OffsetDateTime.now(clock);

        // 앞선 코드를 무효로 만들어 학생 한 명의 살아 있는 코드를 1개로 묶는다 — 반복 발급으로 학원의 6자리 공간을
        // 채워 다른 학생의 코드를 충돌시키는 길을 막는다(BR-215).
        linkCodeRepository.expireLiveCodes(student.getId(), student.getAcademyId(), now, now.minusSeconds(1));
        LinkCode saved = linkCodeRepository.save(LinkCode.forStudent(student.getId(),
                codeGenerator.unusedCode(student.getAcademyId(), now), now.plusMinutes(CODE_VALIDITY_MINUTES), now));
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
        linkWriter.save(guardian, student.get(), now);
        return Optional.of(ChildLinkedResponse.from(student.get()));
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
}
