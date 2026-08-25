package src.backend.account.command;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.academy.entity.Academy;
import src.backend.academy.entity.StaffStatus;
import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.dto.LoginFailureDetail;
import src.backend.account.entity.Account;
import src.backend.account.entity.RefreshToken;
import src.backend.account.repository.AccountRepository;
import src.backend.account.repository.RefreshTokenRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.JwtTokenProvider;

/**
 * 로그인(AUTH-04·05, API_SPEC §2.5) — 자격 대조 → 실패 누적/차단 판정 → 토큰 발급까지 담당한다.
 * 클라이언트 종류(app/web)는 전혀 모른다 — 발급한 원문 토큰을 어떻게 응답에 실을지는
 * {@code AuthController} 가 판단한다(브리프 §3).
 */
@Service
@RequiredArgsConstructor
public class LoginCommandService {

    private final AccountRepository accountRepository;
    private final AcademyRepository academyRepository;
    private final AcademyStaffRepository academyStaffRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final Clock clock;
    @Value("${jwt.refresh-token-validity-seconds}")
    private final long refreshValiditySeconds;

    /**
     * {@code pending}·{@code rejected} 도 로그인은 성공한다(API_SPEC §2.5) — 접근 범위 축소는
     * 계정 상태 게이트(§1.4)가 별도로 담당한다. 이 메서드는 자격 증명과 차단 여부만 본다.
     *
     * <p>미등록 {@code login_id} 도 존재하는 계정의 첫 실패와 <b>본문 형태와 값이 같아야 한다</b> —
     * {@code details.remaining_attempts} 를 한쪽에만 실으면 그 유무가, 양쪽에 싣더라도 값이 갈리면
     * 그 숫자가 곧 계정 존재 신호다(계정 열거, 리뷰 라운드 1 I5 · 라운드 2 I-1). 미등록에는 누적할
     * 카운터가 부재하므로 {@link Account#REMAINING_AFTER_FIRST_FAILURE} 를 싣는다.
     *
     * <p><b>이 조치가 막는 것과 남는 것.</b> 막는 것은 <b>1회 프로브</b>뿐이다 — 2회째부터는 존재
     * 계정이 3·2·1 로 줄고 미등록은 계속 같은 값을 내려 다시 갈린다. 그리고 이 필드를 어떻게 손봐도
     * 열거는 닫히지 않는다 — 상한을 채운 계정은 {@code blocked} 로 전이해 응답이 403
     * {@code AUTH_ACCOUNT_BLOCKED} 로 바뀌고 미등록은 계속 401 이므로, <b>잠금 동작 자체가 열거
     * 채널</b>이고 응답 본문만으로는 원리상 닫을 수 없다. 실질 대응은 시도 빈도 제한이며 그것은
     * Phase 14(운영 게이트)에 등재돼 있다(Ruling 127) — 이 경로에서 만들지 않는다.
     *
     * <p>실패 시 {@link Account#recordLoginFailure} 가 상한 도달을 판정해 {@code blocked} 로
     * 전이시키면, 그 즉시 계정의 유효 refresh 토큰을 전량 무효화한다 — API_SPEC §1.2 는 "차단 시
     * 무효화"만 적고 트리거를 명시하지 않는데, 이 로그인 경로에서의 차단도 그 트리거에 포함시킨
     * 판단이다(Task 4 판단, 보고서 ⑥).
     */
    @Transactional
    public LoginResult login(String loginId, String rawPassword) {
        Account account = accountRepository.findByLoginId(loginId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS,
                        new LoginFailureDetail(Account.REMAINING_AFTER_FIRST_FAILURE)));
        account.assertNotBlocked();

        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!passwordEncoder.matches(rawPassword, account.getPasswordHash())) {
            int remaining = account.recordLoginFailure(now);
            if (account.getStatus() == AccountStatus.BLOCKED) {
                refreshTokenRepository.revokeAllValidByAccountId(account.getId(), now);
                throw new BusinessException(ErrorCode.AUTH_ACCOUNT_BLOCKED);
            }
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, new LoginFailureDetail(remaining));
        }
        assertStaffStillEmployed(account);
        account.recordLoginSuccess(now);

        String accessToken = jwtTokenProvider.createAccessToken(account.getId(), account.getAcademyId(),
                account.getRole(), account.getStatus());
        String refreshToken = jwtTokenProvider.createRefreshToken(account.getId(), account.getAcademyId(),
                account.getRole(), account.getStatus());
        refreshTokenRepository.save(RefreshToken.issue(account.getId(),
                RefreshTokenHasher.sha256Hex(refreshToken), now, now.plusSeconds(refreshValiditySeconds), null));

        String academyName = resolveAcademyName(account);
        return new LoginResult(accessToken, refreshToken, refreshValiditySeconds, account.getId(),
                account.getAcademyId(), account.getRole(), account.getStatus(), academyName);
    }

    /**
     * 퇴사 처리된 관계자의 재로그인을 막는다(API_SPEC §2.5·§6.7·§8.1, Ruling 143).
     *
     * <p>§6.7 의 퇴사 처리는 refresh 토큰을 전량 무효화하지만 그것은 <b>그 순간 열려 있는 세션</b>만
     * 끊는다. 비밀번호를 아는 퇴사자가 다시 로그인하면 {@code role=staff} 권한을 그대로 되찾으므로,
     * §6.7 이 요건으로 규정한 "퇴사 즉시 권한 회수" 가 성립하지 않는다 — 관계자 계정은 학생 개인정보
     * 전체에 접근한다.
     *
     * <p><b>비밀번호 대조를 통과한 뒤에 부른다.</b> {@link Account#assertNotBlocked} 처럼 대조 앞에
     * 두면 아이디 하나만으로 "실재하고 퇴사한 관계자" 를 알려 주는 계정 열거 채널이 늘고, 그 탐색은
     * 실패 카운터를 올리지 않아 <b>횟수 제한도 받지 않는다.</b> {@code blocked} 가 대조 앞인 것과
     * 갈리는데, 그쪽은 이미 상한을 채워 카운터가 더 오를 자리가 부재한 상태라 교환의 내용이 다르다.
     *
     * <p><b>거부 대상은 행이 있고 그 상태가 {@code inactive} 인 경우뿐이다.</b> 행이 아예 없는 것은
     * 퇴사가 아니라 <b>아직 승인 전</b>(§6.4 승인 큐의 축)이다 — 부재를 퇴사로 읽으면 승인을 기다리는
     * 관계자가 자기 상태를 볼 대기 화면(§1.4)에 닿지 못해 가입 흐름 자체가 성립하지 않는다.
     *
     * <p>{@code staff} 에만 질의를 거는 이유는 {@code academy_staff} 를 갖는 역할이 그것뿐이어서다.
     * 역할을 가리지 않으면 학부모·학생·기사·동승자의 매 로그인에 항상 0건인 질의가 붙고, 그 부재를
     * 퇴사로 읽는 구현으로 한 발짝만 미끄러지면 <b>전 사용자가 로그인 불가</b>가 된다. Phase 5·9 가
     * {@code manager}·{@code guardian}·{@code student} 레코드를 만들면 같은 축이 생기며, 일반화 여부는
     * 그 역할들이 실제로 어떻게 갈리는지 드러난 뒤에 정한다(Ruling 143).
     */
    private void assertStaffStillEmployed(Account account) {
        if (account.getRole() != Role.STAFF) {
            return;
        }
        boolean resigned = academyStaffRepository.findByAccountId(account.getId())
                .filter(staff -> staff.getStatus() == StaffStatus.INACTIVE)
                .isPresent();
        if (resigned) {
            throw new BusinessException(ErrorCode.AUTH_STAFF_INACTIVE);
        }
    }

    /** {@code system_admin} 은 소속 학원이 없다(API_SPEC §2.5 {@code academy} = null). */
    private String resolveAcademyName(Account account) {
        if (account.getAcademyId() == null) {
            return null;
        }
        return academyRepository.findById(account.getAcademyId()).map(Academy::getName).orElse(null);
    }
}
