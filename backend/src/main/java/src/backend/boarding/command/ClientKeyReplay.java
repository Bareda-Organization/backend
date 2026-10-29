package src.backend.boarding.command;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.global.persistence.ClientKeyLock;

/**
 * 같은 {@code client_key} 재전송(API_SPEC §1.7)의 공통 절차 — 키 잠금을 잡은 뒤에 이전 처리를 찾아 응답으로 옮긴다. 승하차 · 예외 보고 ·
 * 미승차 연락 · 비상 신고가 같은 순서를 쓴다. 잠금을 찾기 앞에 잡아야 같은 키의 동시 요청 중 뒤 요청이 앞 요청의 커밋을 본다(BR-226).
 */
@Component
@RequiredArgsConstructor
public class ClientKeyReplay {

    private final ClientKeyLock clientKeyLock;

    /**
     * 키가 있으면 잠근 뒤 {@code finder} 로 이전 처리를 찾아 {@code responder} 가 만든 응답을 돌려준다 — 이전 처리가 없거나 키가 없으면 비어 있다.
     * 호출부 트랜잭션 안에서만 부른다.
     */
    public <E, R> Optional<R> replay(UUID clientKey, Function<UUID, Optional<E>> finder, Function<E, R> responder) {
        if (clientKey == null) {
            return Optional.empty();
        }
        clientKeyLock.acquire(clientKey);
        return finder.apply(clientKey).map(responder);
    }
}
