package src.backend.account.command;

import java.time.OffsetDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
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
import src.backend.audit.entity.AuditLog;
import src.backend.audit.repository.AuditLogRepository;
import src.backend.global.common.enums.AccountStatus;
import src.backend.global.common.enums.Role;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.JwtTokenProvider;

/**
 * 로그인의 짧은 쓰기 트랜잭션 — {@link LoginCommandService#login} 이 트랜잭션·행 잠금 밖에서 끝낸 비밀번호 대조
 * 결과를 받아, 계정 행을 잠근 뒤 <b>잠근 행 기준으로</b> 실패 누적·차단 또는 성공 처리·토큰 발급을 한다.
 *
 * <p>별도 빈으로 분리한 이유는 self-invocation 때문이다 — {@code LoginCommandService} 안의 메서드로 두면 그 클래스
 * 자신을 통한 호출이라 {@code @Transactional} 프록시를 거치지 않는다({@code RunConfirmationPersistence} 와 같은 이유).
 */
@Component
@RequiredArgsConstructor
public class LoginSettlement {

    private final AccountRepository accountRepository;
    private final AcademyRepository academyRepository;
    private final AcademyStaffRepository academyStaffRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuditLogRepository auditLogRepository;
    @Value("${jwt.refresh-token-validity-seconds}")
    private final long refreshValiditySeconds;

    /**
     * 계정 행을 잠그고 대조 결과를 확정한다(C-11 · BR-026) — 잠금이 같은 계정의 로그인을 직렬화해 동시 실패가
     * 카운터를 덮어쓰지 않는다.
     *
     * <p><b>잠근 행을 다시 본다.</b> 대조는 잠금 밖에서 돌았으므로 그 사이 다른 요청이 이 계정을 바꿨을 수 있다.
     * ① 차단됐으면 비밀번호가 맞아도 거부한다 — 처음 읽은 상태로 판정하면 성공 처리가 카운터를 0 으로 되돌리고
     * 차단된 계정에 토큰을 발급한다. ② 해시가 바뀌었으면(그 사이 비밀번호 변경·초기화) 앞선 대조 결과를 버리고
     * 잠근 행의 해시로 다시 대조한다 — 옛 결과를 믿으면 바뀌기 전 비밀번호로 로그인되고, 실패로 치면 변경 직전
     * 비밀번호를 옳게 넣은 사용자의 카운터가 오른다. 다시 대조하는 동안은 잠금을 쥐지만 드문 경로라 받아들인다.
     *
     * <p>실패 시 {@link Account#recordLoginFailure} 가 상한 도달을 판정해 {@code blocked} 로
     * 전이시키면, 그 즉시 계정의 유효 refresh 토큰을 전량 무효화한다 — API_SPEC §1.2 는 "차단 시
     * 무효화"만 적고 트리거를 명시하지 않는데, 이 로그인 경로에서의 차단도 그 트리거에 포함시킨
     * 판단이다(Task 4 판단, 보고서 ⑥).
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
     * @param comparedHash 잠금 밖 대조에 쓴 해시 — 잠근 행의 해시와 다르면 {@code matched} 를 버린다
     * @param matched      {@code comparedHash} 로 대조한 결과
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public LoginResult settle(String loginId, String rawPassword, String comparedHash, boolean matched,
            String ip, OffsetDateTime now) {
        // 계정은 삭제·아이디 변경 경로가 부재하다 — 잠금 밖에서 찾은 행은 여기서도 있다
        Account account = accountRepository.findByLoginIdForUpdate(loginId).orElseThrow();
        account.assertNotBlocked();
        boolean verified = account.getPasswordHash().equals(comparedHash)
                ? matched
                : passwordEncoder.matches(rawPassword, account.getPasswordHash());

        if (!verified) {
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

        Academy academy = resolveAcademy(account);
        return new LoginResult(accessToken, refreshToken, refreshValiditySeconds, account.getId(),
                account.getAcademyId(), account.getRole(), account.getStatus(),
                academy == null ? null : academy.getName(), academy == null ? null : academy.getContact());
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
    private Academy resolveAcademy(Account account) {
        if (account.getAcademyId() == null) {
            return null;
        }
        return academyRepository.findById(account.getAcademyId()).orElse(null);
    }
}
