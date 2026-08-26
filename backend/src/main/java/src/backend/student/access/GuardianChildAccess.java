package src.backend.student.access;

import java.util.List;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.student.entity.Guardian;
import src.backend.student.repository.GuardianRepository;
import src.backend.student.repository.GuardianStudentRepository;
import src.backend.student.repository.LinkedChild;

/**
 * 학부모 API 의 접근 범위 판정 — "이 요청 주체는 어느 자녀를 만질 수 있는가" 를 답하는 <b>유일한
 * 지점</b>이다(API_SPEC §1.5 · Ruling 117).
 *
 * <p>경로마다 손으로 검사하지 않는 이유가 이 클래스의 존재 이유다. 복제하면 다음에 추가되는 학부모
 * 경로가 <b>검사를 빠뜨린 채 태어나고</b>, 그 상태로도 기존 테스트는 전부 초록이다 — 인가 결함은
 * "통과하는 빈 테스트" 와 구별되지 않는 형태라 사고가 화면에 드러나지 않는다.
 *
 * <p>Phase 2 는 이 판정을 만들지 않았다. 당시 소비자가 0곳이라
 * {@code p2-controller-conventions §1}("판정이 2곳 이상에서 필요할 때만") 이 만들지 말라고 했고,
 * {@code global/} 에 두면 {@code global} → {@code student} 역방향 의존이 생기기 때문이다(Ruling 117).
 * 두 조건이 지금 함께 풀렸다 — 소비자가 자녀 목록(§3.1)·연결(§3.4)·요일별 주소(§3.7)로 늘었고,
 * {@code student} 모듈 안에 두면 역방향 의존이 부재하다.
 *
 * <p>범위를 좁히는 축이 <b>둘</b>이다. 요청 주체 → {@link Guardian}(계정 경유)과
 * {@link Guardian} → 자녀(연결 경유). 앞을 건너뛰면 요청 본문의 보호자 식별자를 믿게 되고, 뒤를
 * 건너뛰면 남의 자녀에 닿는다.
 */
@Component
@RequiredArgsConstructor
public class GuardianChildAccess {

    private final GuardianRepository guardianRepository;

    private final GuardianStudentRepository guardianStudentRepository;

    /**
     * 요청 주체의 보호자 레코드를 꺼낸다 — 없으면 {@code 403 FORBIDDEN}(§1.11 역할 권한 밖 호출).
     *
     * <p>보호자 식별자는 <b>토큰의 {@code accountId}</b> 로만 얻는다. 요청 본문·쿼리로 받으면 그 값이
     * 격리를 우회하는 가장 쉬운 경로가 된다(§1.5).
     *
     * <p>{@code guardian} 을 여기서 <b>만들지 않는다</b>. {@code guardian.account_id} 가 FK UK NN 이라
     * 계정이 반드시 선행하고, 그 연결을 만드는 것은 가입 승인(AUTH-11)이다(Ruling 161) — 없는 계정에
     * 보호자를 지어 주면 승인을 거치지 않은 사람이 자녀 연결 화면에 들어선다.
     */
    public Guardian requireGuardian(AuthUser requester) {
        return guardianRepository.findByAccountId(requester.accountId())
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
    }

    /**
     * 이 요청 주체가 지목한 자녀에 닿을 수 있는지 판정한다 — 연결이 없거나 해제됐으면
     * {@code 403 FORBIDDEN}(§3.7 {@code 403 FORBIDDEN} — 연결 부재 자녀).
     *
     * <p>{@code {id}} 로 자원을 지목한 경로라 Ruling 163 의 규칙만 보면 {@code 404} 이나,
     * <b>정본 §3.7 이 이 경로에 {@code 403} 을 명시</b>한다. 정본이 명시한 곳에서는 정본이 이긴다.
     */
    public void assertLinkedChild(AuthUser requester, Long studentId) {
        Guardian guardian = requireGuardian(requester);
        if (!guardianStudentRepository.existsActiveLink(guardian.getId(), studentId, guardian.getAcademyId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    /**
     * 이 보호자가 볼 수 있는 자녀 전부(ATT-03, §3.1) — 해제된 연결은 빠진다.
     *
     * <p>단건 판정({@link #assertLinkedChild})과 <b>같은 클래스</b>에 둔다. 두 곳에 나누면 한쪽만
     * 고쳐져 "단건은 막는데 목록은 새는" 상태가 되는데, 그것이 {@code ARCHITECTURE §6.1} 이 지목한
     * 바로 그 사고 지점이다.
     */
    public List<LinkedChild> linkedChildren(Guardian guardian) {
        return guardianStudentRepository.findLinkedChildren(guardian.getId(), guardian.getAcademyId());
    }
}
