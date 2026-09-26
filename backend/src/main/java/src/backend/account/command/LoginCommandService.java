package src.backend.account.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import src.backend.account.dto.LoginFailureDetail;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.audit.entity.AuditLog;
import src.backend.audit.repository.AuditLogRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/**
 * 로그인(AUTH-04·05, API_SPEC §2.5) — 자격 대조 → 실패 누적/차단 판정 → 토큰 발급까지 담당한다.
 * 클라이언트 종류(app/web)는 전혀 모른다 — 발급한 원문 토큰을 어떻게 응답에 실을지는
 * {@code AuthController} 가 판단한다(브리프 §3).
 */
@Service
@RequiredArgsConstructor
public class LoginCommandService {

    /**
     * 미등록 아이디의 대조 상대 — 존재 계정과 같은 BCrypt 계산을 거치게 해 응답 시간으로 계정 존재가 드러나지 않게
     * 한다(BR-130). 어떤 원문과도 맞지 않도록 버리는 난수로 만든다. 운영 인코더와 같은 BCrypt 기본 강도(10)다.
     */
    private static final String UNKNOWN_ACCOUNT_HASH =
            new BCryptPasswordEncoder().encode(UUID.randomUUID().toString());

    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final AuditLogRepository auditLogRepository;
    private final LoginSettlement loginSettlement;

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
     * <p><b>감사(SYS-01, Phase 14 T1 목표 2)</b> — {@code login_success}·{@code login_fail}·
     * {@code block} 세 이벤트만 남긴다. {@link Account#assertNotBlocked} 가 던지는 차단 계정의 대조
     * 전 403 은 남기지 않는다 — 그 요청은 자격 증명을 아직 대조하지 않았고, 실패 카운터도 올리지
     * 않으므로 새 로그인 시도가 아니라 이미 알려진 차단 상태의 반복 확인일 뿐이다. {@code now} 계산을
     * 메서드 첫 줄로 옮긴 것은 미등록 {@code login_id} 분기에서도 감사 시각이 필요해서다(원래는 대조
     * 직전에 있었다).
     *
     * <p><b>비밀번호 대조(BCrypt)는 트랜잭션·행 잠금 밖에서 한다.</b> BCrypt 는 일부러 느린 연산(요청당 수십~수백
     * ms)이라 잠금 트랜잭션 안에 두면 그 시간만큼 DB 연결이 묶이고, 등원 전 로그인이 몰리면 풀이 대조를 기다리는
     * 요청으로 찬다(FIX-LOAD2 §3-1). 그래서 ① 잠금 없이 계정을 읽고 ② 대조한 뒤 ③ {@link LoginSettlement} 가 행을
     * 잠그고 <b>잠근 행 기준으로</b> 차단·해시 변경을 다시 확인해 결과를 기록한다. 실패 기록·감사 행의 커밋
     * 규칙({@code noRollbackFor})도 그쪽에 있다. 미등록 아이디의 감사 행은 저장소 {@code save} 가 여는 자기
     * 트랜잭션에서 커밋된다.
     *
     * <p>{@code ip} 는 호출자({@code AuthController})가 해석해 건넨다 — Service 는 HTTP 를 직접 알지
     * 않는다(BR-131, {@code CODE_CONVENTIONS §12}).
     */
    public LoginResult login(String loginId, String rawPassword, String ip) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Account snapshot = accountRepository.findByLoginId(loginId).orElse(null);
        if (snapshot == null) {
            passwordEncoder.matches(rawPassword, UNKNOWN_ACCOUNT_HASH);
            auditLogRepository.save(AuditLog.forLoginFail(null, null, loginId, ip, now));
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS,
                    new LoginFailureDetail(Account.REMAINING_AFTER_FIRST_FAILURE));
        }
        snapshot.assertNotBlocked();

        boolean matched = passwordEncoder.matches(rawPassword, snapshot.getPasswordHash());
        return loginSettlement.settle(loginId, rawPassword, snapshot.getPasswordHash(), matched, ip, now);
    }
}
