package src.backend.global.security;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.account.repository.AccountRepository;

/**
 * 계정이 <b>권한을 잃었는가</b>(Ruling 880) — 차단 · 퇴사한 관계자 · 삭제된 매니저면 그 계정이 이미 받은 access 토큰은
 * 다음 요청부터 {@code 401 TOKEN_EXPIRED} 다. 토큰은 발급 때의 상태를 싣고 있어 서버가 현재 상태를 다시 묻지 않으면
 * 최대 access 유효 시간(15분) 동안 옛 권한이 그대로 통한다.
 *
 * <p>로그인도 같은 판정을 쓴다(Ruling 891) — 비밀번호 대조를 통과한 계정이 이미 권한을 잃었으면 {@code 403 AUTH_STAFF_INACTIVE} 로
 * 거절한다. 로그인은 200 인데 첫 요청부터 401 이 되풀이되는 것을 막고, 두 자리의 기준이 어긋나지 않게 한다.
 *
 * <p>캐시를 두지 않는다 — 기본키 한 건 조회라 요청당 비용이 작고(질의 1회), 캐시를 두면 "다음 요청부터" 가 "캐시 만료 뒤부터" 로
 * 바뀐다. 상태가 바뀌는 경로마다 무효화를 붙이는 방식은 경로 하나를 빠뜨리면 응답이 200 그대로라 어떤 검사도 못 잡는다.
 */
@Component
@RequiredArgsConstructor
public class AccountStanding {

    /** PostgreSQL 바인드 파라미터 한도(32,767)에 한참 못 미치는 한 번의 질의 크기. */
    private static final int QUERY_CHUNK = 1000;

    private final AccountRepository accountRepository;

    /** 이 계정이 권한을 잃었으면 참 — 계정 행이 없으면 거짓(알 수 없는 것을 잃었다고 읽지 않는다). */
    public boolean isLost(Long accountId) {
        return !accountRepository.findIdsWithLostStanding(List.of(accountId)).isEmpty();
    }

    /** 주어진 계정 중 권한을 잃은 계정 — 열린 WebSocket 세션 점검이 계정 수만큼 묻지 않고 묶음으로 묻는다. */
    public Set<Long> lostAmong(Collection<Long> accountIds) {
        List<Long> ids = List.copyOf(accountIds);
        Set<Long> lost = new HashSet<>();
        for (int from = 0; from < ids.size(); from += QUERY_CHUNK) {
            lost.addAll(accountRepository.findIdsWithLostStanding(ids.subList(from, Math.min(from + QUERY_CHUNK, ids.size()))));
        }
        return lost;
    }
}
