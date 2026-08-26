package src.backend.notification.domain.spec;

/**
 * 알림 문구 생성 포트(§7 규칙 12 교체 축) — <b>문구·다국어가 바뀔 축</b>이다
 * (ARCHITECTURE §3.2.1).
 *
 * <p>알림 종류마다 문구의 재료가 다르므로({@code signup_decided} 는 수락 여부와 거절 사유,
 * {@code boarding} 은 자녀 이름과 호차) 재료 타입을 타입 파라미터로 받는다. 종류가 늘어날 때
 * <b>이 인터페이스도 레지스트리도 고치지 않고 구현체만 더한다</b> — 호출부는 Spring 이 제네릭
 * 타입으로 골라 주입한 {@code NotificationComposer<그 재료>} 를 받는다.
 *
 * <p>재료 타입을 공통 상위 타입으로 묶지 않은 이유는 그렇게 하면 구현체마다 형변환이 필요해지고,
 * 그 형변환이 <b>런타임에야</b> 틀리기 때문이다. 타입 파라미터는 잘못된 짝을 컴파일 단계에서 막는다.
 *
 * @param <S> 문구의 재료. 알림 종류가 정한다
 */
public interface NotificationComposer<S> {

    /** 재료에서 제목·본문을 만든다. 저장·발송은 하지 않는다. */
    NotificationMessage compose(S subject);
}
