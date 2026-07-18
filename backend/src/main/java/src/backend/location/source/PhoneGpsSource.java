package src.backend.location.source;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 실 GPS 소스 — 학생 스마트폰이 자기 좌표를 서버로 직접 push 하는 방식(pull 이 아니라 push).
 *
 * <p>그래서 서버가 주기적으로 끌어올 게 없어 {@link #tick()} 은 no-op 이고, 실제 좌표는
 * {@code POST /api/locations}({@code LocationController.report}) → {@code LocationCommandService.reportSelf}
 * 경로로 들어온다. Mock 과의 유일한 차이는 "누가 좌표를 만드느냐"뿐이며, 저장·조회는 동일하다.
 *
 * <p>MVP 에서는 기본 비활성({@code app.location.gps.enabled=false})이고, 실 연동 시
 * 이 플래그를 켜고 Mock 을 끄면 교체가 끝난다.
 */
@Component
public class PhoneGpsSource implements LocationSource {

    private final boolean enabled;

    public PhoneGpsSource(@Value("${app.location.gps.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public boolean isActive() {
        return enabled;
    }

    @Override
    public void tick() {
        // push 방식이라 서버가 끌어올 게 없다 — 좌표는 학생 앱의 POST /api/locations 로 들어온다.
    }

    @Override
    public String label() {
        return "phone-gps";
    }
}
