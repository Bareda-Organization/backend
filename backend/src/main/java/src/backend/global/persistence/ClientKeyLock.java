package src.backend.global.persistence;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;

/**
 * 같은 {@code client_key} 의 요청을 트랜잭션 끝까지 한 줄로 세운다(BR-226) — 오프라인 큐가 응답을 놓친 요청을 같은 키로
 * 다시 보내면 첫 요청이 아직 처리 중일 때 두 요청이 모두 "재전송 아님" 을 읽고 UNIQUE 위반으로 500 이 났다. 재전송 조회 <b>앞</b>에서
 * 이 잠금을 잡으면 뒤 요청은 앞 요청이 커밋한 뒤에야 조회해 재생 응답을 받는다. 키가 다른 요청끼리는 서로 기다리지 않는다.
 *
 * <p>Postgres 트랜잭션 수준 자문 잠금이라 커밋·롤백에서 저절로 풀린다 — 행 잠금과 달리 아직 행이 없는 키도 잠글 수 있다.
 */
@Component
@RequiredArgsConstructor
public class ClientKeyLock {

    private final EntityManager entityManager;

    /** 호출부 트랜잭션 안에서만 부른다 — 잠금이 그 커밋까지 유지돼야 한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void acquire(UUID clientKey) {
        entityManager.createNativeQuery("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext(:key))) AS locked")
                .setParameter("key", "client_key:" + clientKey)
                .getSingleResult();
    }
}
