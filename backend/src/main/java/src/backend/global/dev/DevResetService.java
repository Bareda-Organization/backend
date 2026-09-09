package src.backend.global.dev;

import java.util.Set;

import org.flywaydb.core.Flyway;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * 개발용 초기화 — DB 를 시드 적재 직후 상태로 되돌린다.
 *
 * <p>스스로 {@code clean()} 을 부르지 않고 {@link FlywayMigrationStrategy}(=
 * {@code LocalFlywayCleanStrategy})에 그대로 위임하는 것이 이 클래스의 요점이다. 기동 시 초기화와
 * <b>같은 코드·같은 안전장치</b>를 타므로, 누군가 그 안전장치를 약화시키면 두 경로가 함께 깨진다 —
 * 여기서 {@code flyway.clean()} 을 직접 부르면 안전장치가 두 벌이 되고, 한쪽만 고쳐지는 순간
 * 그 사실이 아무 시험에도 걸리지 않는다.
 *
 * <p>Redis 의 최신 좌표까지 지우는 이유는 DB 만 되돌리면 <b>지워진 회차의 좌표가 캐시에 남아</b>
 * 실시간 위치 조회가 존재하지 않는 회차를 가리키기 때문이다(TTL 이 지나기 전까지).
 */
@Service
@RequiredArgsConstructor
public class DevResetService {

    /** 위치 캐시 키 형태 — {@code RunPositionRedisListener} 가 쓰는 것과 같은 접두사다. */
    private static final String POSITION_KEY_PATTERN = "run:*:position";

    private final Flyway flyway;

    private final FlywayMigrationStrategy migrationStrategy;

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * DB 를 비우고 스키마·시드를 다시 적재한 뒤 위치 캐시를 지운다.
     *
     * @return 지운 위치 캐시 키 개수 — 되돌린 사실을 호출자가 눈으로 확인할 수 있게 한다
     */
    public int reset() {
        migrationStrategy.migrate(flyway);
        Set<String> positionKeys = stringRedisTemplate.keys(POSITION_KEY_PATTERN);
        if (positionKeys == null || positionKeys.isEmpty()) {
            return 0;
        }
        stringRedisTemplate.delete(positionKeys);
        return positionKeys.size();
    }
}
