package src.backend.global.error;

import org.jspecify.annotations.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

/**
 * STOMP ERROR 프레임에 {@link ErrorCode} 이름을 실어, WebSocket 거부를 REST 와 같은 어휘로 알린다.
 *
 * <p>{@link GlobalExceptionHandler} 옆에 두는 이유는 두 변환기가 <b>같은 사전을 쓰는지 한눈에 보이게</b>
 * 하기 위함이다 — 같은 거부에 어휘가 둘이면 클라이언트가 같은 사실을 REST 의 {@code error.code} 와
 * WebSocket 의 예외 문구로 두 번 배워야 한다.
 *
 * <p>기본 구현은 예외의 {@code getMessage()} 를 그대로 싣는데, 채널 인터셉터가 던진 예외는 스프링이
 * {@code MessageDeliveryException} 으로 감싸므로 프레임에 "Failed to send message to
 * ExecutorSubscribableChannel[clientInboundChannel]" 이라는 <b>원인을 특정하지 못하는 문구</b>만 남는다.
 * 그래서 원인 사슬을 훑어 {@link BusinessException} 을 찾는다.
 */
public class StompErrorFrameHandler extends StompSubProtocolErrorHandler {

    /** 원인 사슬을 따라가며 볼 최대 깊이 — 순환 참조가 있어도 여기서 멈춘다. */
    private static final int MAX_CAUSE_DEPTH = 10;

    /**
     * ERROR 프레임의 {@code message} 헤더를 에러 코드 이름으로 바꾼다 — 코드를 못 찾으면 기본 동작 그대로 둔다.
     *
     * <p>{@code handleInternal} 을 고르는 이유는 상위가 영수증 id 대응을 이미 마친 뒤 호출하는 지점이라,
     * 그 처리를 다시 구현하지 않고 헤더 한 줄만 덮어쓸 수 있어서다.
     */
    @Override
    protected Message<byte[]> handleInternal(StompHeaderAccessor errorHeaderAccessor, byte[] errorPayload,
            @Nullable Throwable cause, @Nullable StompHeaderAccessor clientHeaderAccessor) {

        ErrorCode code = errorCodeOf(cause);
        if (code != null) {
            errorHeaderAccessor.setMessage(code.name());
        }
        return super.handleInternal(errorHeaderAccessor, errorPayload, cause, clientHeaderAccessor);
    }

    private static @Nullable ErrorCode errorCodeOf(@Nullable Throwable cause) {
        Throwable current = cause;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof BusinessException business) {
                return business.getErrorCode();
            }
            current = current.getCause();
        }
        return null;
    }
}
