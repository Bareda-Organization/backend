package src.backend.account.command;

import java.net.InetAddress;
import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

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
import src.backend.audit.entity.AuditLog;
import src.backend.audit.repository.AuditLogRepository;
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
    private final AuditLogRepository auditLogRepository;
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
     *
     * <p><b>감사(SYS-01, Phase 14 T1 목표 2)</b> — {@code login_success}·{@code login_fail}·
     * {@code block} 세 이벤트만 남긴다. {@link Account#assertNotBlocked} 가 던지는 차단 계정의 대조
     * 전 403 은 남기지 않는다 — 그 요청은 자격 증명을 아직 대조하지 않았고, 실패 카운터도 올리지
     * 않으므로 새 로그인 시도가 아니라 이미 알려진 차단 상태의 반복 확인일 뿐이다. {@code now} 계산을
     * 메서드 첫 줄로 옮긴 것은 미등록 {@code login_id} 분기에서도 감사 시각이 필요해서다(원래는 대조
     * 직전에 있었다).
     *
     * <p>감사 기록을 {@code AuditRecorder}(별도 트랜잭션, {@link
     * src.backend.audit.service.AuditRecorder}) 가 아니라 이 메서드와 <b>같은 트랜잭션</b>에서
     * {@code auditLogRepository} 로 직접 쓴다 — 실패 카운터 증가도 뒤따르는 {@code BusinessException}
     * 과 같은 트랜잭션에 있어야 자격 대조 결과와 감사 기록이 하나의 단위로 묶인다(이 클래스의 기존
     * 설계). 문제는 그 뒤따르는 {@code BusinessException} 이 Spring 의 기본 롤백 규칙(모든
     * {@code RuntimeException} 에서 롤백)을 그대로 타면 <b>이 메서드가 방금 쓴 것 전부가 함께
     * 사라진다</b>는 점이다 — 실패 카운터 증가도, 이 메서드가 던진 {@code login_fail}·{@code block}
     * 감사 행도 예외이지 규칙이 아니다({@code Ruling 282}). {@code AuthControllerTest} 의 클래스 전체
     * {@code @Transactional} 이 매 요청을 하나의 트랜잭션으로 묶어 실제 커밋 여부를 가려 왔기 때문에
     * 이 결함이 오래 남아 있었다(같은 테스트를 {@code Propagation.NOT_SUPPORTED} 로 트랜잭션 밖에
     * 두면 실제로 커밋이 안 되는 것이 드러난다). 그래서 아래 {@code noRollbackFor} 로 이 메서드가
     * 던지는 {@code BusinessException} 은 롤백 대상에서 제외한다 — 실패 응답을 던지는 것과 그 실패를
     * 기록하는 것은 같은 트랜잭션 안에서 둘 다 커밋돼야 하는, 서로 다른 두 가지 일이다.
     *
     * <p>{@code ip} 는 {@link RequestContextHolder} 로 얻는다 — 해석 규칙은 {@link #resolveClientIp()}.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public LoginResult login(String loginId, String rawPassword) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        String ip = resolveClientIp();
        Account account = accountRepository.findByLoginIdForUpdate(loginId).orElse(null);
        if (account == null) {
            auditLogRepository.save(AuditLog.forLoginFail(null, null, loginId, ip, now));
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS,
                    new LoginFailureDetail(Account.REMAINING_AFTER_FIRST_FAILURE));
        }
        account.assertNotBlocked();

        if (!passwordEncoder.matches(rawPassword, account.getPasswordHash())) {
            int remaining = account.recordLoginFailure(now);
            auditLogRepository.save(
                    AuditLog.forLoginFail(account.getAcademyId(), account.getId(), loginId, ip, now));
            if (account.getStatus() == AccountStatus.BLOCKED) {
                refreshTokenRepository.revokeAllValidByAccountId(account.getId(), now);
                auditLogRepository.save(
                        AuditLog.forLoginBlock(account.getAcademyId(), account.getId(), loginId, ip, now));
                throw new BusinessException(ErrorCode.AUTH_ACCOUNT_BLOCKED);
            }
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, new LoginFailureDetail(remaining));
        }
        assertStaffStillEmployed(account);
        account.recordLoginSuccess(now);
        auditLogRepository.save(
                AuditLog.forLoginSuccess(account.getAcademyId(), account.getId(), loginId, ip, now));

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

    /**
     * 요청 발신 IP(감사 {@code ip}, Phase 14 T1 목표 2).
     *
     * <p><b>{@code X-Real-IP} 를 쓰고 {@code X-Forwarded-For} 는 쓰지 않는다</b>(BR-062). nginx 는 받은
     * {@code X-Forwarded-For} 뒤에 실제 주소를 덧붙이므로 첫 값은 요청자가 적은 값이라 접속 이력을 위조할 수 있다.
     * {@code X-Real-IP} 는 nginx 가 {@code $remote_addr} 로 항상 덮어쓰고(`infra/proxy/nginx*.conf`), 운영
     * 백엔드는 프록시 밖에 포트를 열지 않는다. 프록시 없이 직접 붙은 요청(로컬)은 원격 주소로 내려간다.
     *
     * <p>IP 표기가 아니면 {@code null} 이다 — {@code audit_log.ip} 가 {@code inet} 이라 그대로 저장하면 로그인
     * 자체가 500 이 된다. 요청 컨텍스트가 없는 자리(배치·테스트 등)도 {@code null} 이다.
     */
    private String resolveClientIp() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return null;
        }
        HttpServletRequest request = servletAttributes.getRequest();
        String realIp = request.getHeader("X-Real-IP");
        String candidate = realIp != null && !realIp.isBlank() ? realIp.trim() : request.getRemoteAddr();
        try {
            return InetAddress.ofLiteral(candidate).getHostAddress();
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }
}
