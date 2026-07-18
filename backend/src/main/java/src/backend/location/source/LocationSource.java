package src.backend.location.source;

/**
 * 위치 좌표가 "어디서 흘러들어오는가"를 추상화한 포트(인터페이스).
 *
 * <p>MVP 는 {@link MockLocationSource}(정해진 경로를 따라 좌표를 생성하는 시뮬레이터)가 활성이고,
 * 실 GPS 전환 시에는 {@link PhoneGpsSource}(학생 앱이 직접 좌표를 push)로 바꾼다 —
 * 스케줄러·서비스·저장소는 그대로 두고 이 구현체(와 설정 플래그)만 교체하면 된다.
 *
 * <p>{@code app.location.mock.enabled} / {@code app.location.gps.enabled} 로 각 소스의 활성 여부를 켠다.
 */
public interface LocationSource {

    /** 이 소스가 지금 좌표를 공급하는가(비활성이면 스케줄러가 건너뛴다). */
    boolean isActive();

    /**
     * 스케줄러가 주기마다 호출 — 관장하는 학생들의 위치를 한 스텝 진행시켜 저장 경로로 밀어넣는다.
     * push 방식(실 GPS)처럼 서버가 끌어올 게 없는 소스는 no-op 이다.
     */
    void tick();

    /** 로그·진단용 소스 이름. */
    String label();
}
