package src.backend.account.command;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import src.backend.academy.repository.AcademyRepository;
import src.backend.academy.repository.AcademyStaffRepository;
import src.backend.account.repository.AccountRepository;
import src.backend.account.repository.RefreshTokenRepository;
import src.backend.audit.repository.AuditLogRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.security.JwtTokenProvider;

/**
 * 미등록 아이디도 비밀번호 대조를 한 번 한다(BR-130) — 대조를 건너뛰면 BCrypt 계산(수십 ms) 유무로 응답 시간이
 * 갈려, 응답 본문을 맞춰 둔 계정 열거 방어(§2.5)가 시간 한 번으로 무너진다.
 */
class LoginCommandServiceTest {

    private final AccountRepository accountRepository = mock(AccountRepository.class);

    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

    private final LoginCommandService service = new LoginCommandService(accountRepository,
            mock(AcademyRepository.class), mock(AcademyStaffRepository.class), mock(RefreshTokenRepository.class),
            passwordEncoder, mock(JwtTokenProvider.class), Clock.systemUTC(), mock(AuditLogRepository.class), 3600L);

    @Test
    void 미등록_아이디도_비밀번호_대조를_한_번_한다() {
        given(accountRepository.findByLoginIdForUpdate("nobody")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.login("nobody", "guess", null)).isInstanceOf(BusinessException.class);

        verify(passwordEncoder, times(1)).matches(anyString(), anyString());
    }
}
