package src.backend.location.source;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 위치 소스를 주기적으로 굴리는 드라이버 — 활성 {@link LocationSource} 들의 {@code tick()} 을 호출한다.
 *
 * <p>스케줄러는 어떤 소스가 Mock 인지 실 GPS 인지 알 필요가 없다(다형성). Spring 이 모든
 * {@code LocationSource} 구현체를 주입해주고, 여기서는 활성인 것만 굴린다 — 소스를 추가/교체해도
 * 이 클래스는 바뀌지 않는다. 주기는 {@code app.location.tick-ms}(기본 3초).
 */
@Component
public class LocationSimulationScheduler {

    private static final Logger log = LoggerFactory.getLogger(LocationSimulationScheduler.class);

    private final List<LocationSource> sources;

    public LocationSimulationScheduler(List<LocationSource> sources) {
        this.sources = sources;
    }

    @Scheduled(fixedDelayString = "${app.location.tick-ms:3000}")
    public void tick() {
        for (LocationSource source : sources) {
            if (!source.isActive()) {
                continue;
            }
            try {
                source.tick();
            } catch (Exception e) {
                // 한 소스의 실패가 다음 틱·다른 소스를 막지 않도록 삼킨다(로그만 남김).
                log.warn("[location] 소스 '{}' 틱 실패: {}", source.label(), e.getMessage());
            }
        }
    }
}
