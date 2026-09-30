package src.backend.audit.query;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import src.backend.academy.entity.Academy;
import src.backend.academy.repository.AcademyRepository;
import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.audit.dto.AuditActorResponse;

/**
 * 감사 화면의 행위자 찾기(R46 감사, Ruling 447) — 계정 ID 를 손으로 치지 않고 이름·로그인 아이디 일부로 고른다.
 *
 * <p>페이징이 없고 {@link #MAX_RESULTS} 건에서 자른다 — 사람이 글자를 더 쳐서 좁히는 자리이지 훑는 목록이 아니다.
 */
@Service
@RequiredArgsConstructor
public class AuditActorQueryService {

    /** 한 번에 돌려주는 최대 건수. */
    static final int MAX_RESULTS = 20;

    private final AccountRepository accountRepository;

    private final AcademyRepository academyRepository;

    /** 검색어가 공백뿐이면 빈 목록이다 — 그대로 조회하면 전 계정이 걸린다. */
    public List<AuditActorResponse> search(String q) {
        if (q == null || q.isBlank()) {
            return List.of();
        }
        List<Account> accounts = accountRepository.searchByNameOrLoginId(q.trim(), Limit.of(MAX_RESULTS));
        Map<Long, String> academyNames = academyNamesOf(accounts);
        return accounts.stream()
                .map(account -> new AuditActorResponse(String.valueOf(account.getId()), account.getName(),
                        account.getLoginId(), account.getRole().name().toLowerCase(Locale.ROOT),
                        academyNames.get(account.getAcademyId())))
                .toList();
    }

    /** 한 번에 모은다 — 계정마다 조회하면 건수만큼 질의가 는다. 소속 없는 계정은 키가 {@code null} 이라 {@link HashMap} 이 받는다. */
    private Map<Long, String> academyNamesOf(List<Account> accounts) {
        List<Long> academyIds = accounts.stream().map(Account::getAcademyId).filter(id -> id != null).distinct().toList();
        return academyRepository.findAllById(academyIds).stream()
                .collect(Collectors.toMap(Academy::getId, Academy::getName, (first, second) -> first,
                        HashMap::new));
    }
}
