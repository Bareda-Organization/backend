package src.backend.account.command;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.academy.command.TemporaryPasswordGenerator;
import src.backend.account.entity.Account;
import src.backend.account.entity.PhoneNumber;
import src.backend.account.repository.AccountRepository;
import src.backend.account.repository.RefreshTokenRepository;
import src.backend.global.common.enums.Role;

/**
 * 전화번호 복구의 계정 쪽 일 — 복구 대상 역할, 임시 비밀번호(해시는 트랜잭션 밖에서 미리 만든다) 준비·교체, 아이디 안내 문자 본문.
 * 트랜잭션은 걸지 않으며 {@link AccountRecoveryCommandService} 의 대조 트랜잭션 안에서 불린다.
 */
@Component
@RequiredArgsConstructor
class RecoveryTemporaryCredentials {

    /** 문자 복구 대상 역할 — 관계자·메인 관리자는 제외(Ruling 513). */
    static final Set<Role> RECOVERABLE_ROLES = Set.of(Role.PARENT, Role.STUDENT, Role.DRIVER, Role.ESCORT);

    private final AccountRepository accountRepository;

    private final RefreshTokenRepository refreshTokenRepository;

    private final TemporaryPasswordGenerator temporaryPasswordGenerator;

    private final PasswordEncoder passwordEncoder;

    /** 임시 비밀번호 원문과 저장할 해시 — 해시는 트랜잭션 밖에서 미리 만든다. */
    record TemporaryPassword(String plain, String hash) {
    }

    /** 같은 번호의 복구 대상 계정마다 임시 비밀번호와 해시를 만든다 — 계정 id 로 찾아 쓴다. */
    Map<Long, TemporaryPassword> prepare(String phone) {
        return accountRepository.findAllByPhoneAndRoleIn(PhoneNumber.digits(phone), RECOVERABLE_ROLES).stream()
                .collect(Collectors.toMap(Account::getId, account -> issueTemporaryPassword()));
    }

    private TemporaryPassword issueTemporaryPassword() {
        String plain = temporaryPasswordGenerator.generate();
        return new TemporaryPassword(plain, passwordEncoder.encode(plain));
    }

    /** 아이디 안내 문자 본문 — 같은 번호의 계정 아이디를 모두 담는다. */
    String loginIdText(List<Account> accounts) {
        return "[바래다] 아이디 " + accounts.stream().map(Account::getLoginId).collect(Collectors.joining(", "));
    }

    /**
     * 같은 번호의 계정마다 임시 비밀번호로 바꾸고 문자 본문을 만든다. 문자함에 평문으로 남는 값이라 강제 변경 표식을 켠다
     * ({@link Account#issueTemporaryPassword} · Ruling 785). refresh 토큰 무효화는 영속성 컨텍스트를 비우므로
     * 비밀번호 변경이 전부 끝난 뒤에 한다({@code AccountPasswordResetCommandService} 와 같은 순서).
     */
    String resetPasswords(List<Account> accounts, OffsetDateTime now, Map<Long, TemporaryPassword> prepared) {
        StringBuilder text = new StringBuilder("[바래다]");
        for (Account account : accounts) {
            TemporaryPassword temporary = prepared.containsKey(account.getId()) ? prepared.get(account.getId())
                    : issueTemporaryPassword();
            account.issueTemporaryPassword(temporary.hash());
            text.append(" 아이디 ").append(account.getLoginId()).append(" 임시 비밀번호 ").append(temporary.plain())
                    .append(" /");
        }
        text.append(" 로그인 뒤 바로 바꿔 주세요");
        for (Account account : accounts) {
            refreshTokenRepository.revokeAllValidByAccountId(account.getId(), now);
        }
        return text.toString();
    }
}
