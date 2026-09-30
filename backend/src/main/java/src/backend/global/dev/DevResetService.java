package src.backend.global.dev;

import java.time.Clock;
import java.sql.SQLException;
import java.time.LocalDate;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import src.backend.location.infrastructure.RunPositionStore;
import src.backend.request.preview.spec.ApprovalPreviewCache;
import src.backend.schedule.command.RunGenerationService;

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
 *
 * <p>같은 이유로 승인 미리보기 캐시({@link ApprovalPreviewCache})도 비운다 — 안 비우면 리셋 직후
 * 첫 조회가 "이전 캐시를 교체했다" 는 이유만으로 {@code stale=true} 로 잘못 분류된다.
 *
 * <p>조건 두 겹은 {@link DevResetController} 와 <b>같은 것을 달아야 한다.</b> 위임 대상인
 * {@code LocalFlywayCleanStrategy} 자체가 {@code @Profile("local")} 이라, 이 빈만 조건 없이 두면
 * {@code local} 이 아닌 프로파일에서 <b>주입 대상이 없어 기동이 실패한다</b> — 컨트롤러가 안 떠도
 * 서비스는 뜨기 때문이고, 실제로 {@code load} 프로파일 기동이 그렇게 막혔다(2026-09-09).
 */
@Service
@Profile("local")
@ConditionalOnProperty(name = "app.dev-tools.reset.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class DevResetService {

    private static final Logger log = LoggerFactory.getLogger(DevResetService.class);

    /** Postgres 교착 감지 SQLState(40P01) — 두 트랜잭션이 서로의 잠금을 기다려 한쪽이 중단됐다는 뜻이다. */
    private static final String DEADLOCK_SQL_STATE = "40P01";

    /** 교착이 이어질 때 포기하는 횟수 — 매 시도가 교착 확률이 낮은 짧은 창이라 몇 번이면 충분하다. */
    static final int MAX_MIGRATE_ATTEMPTS = 5;

    private final Flyway flyway;

    private final FlywayMigrationStrategy migrationStrategy;

    private final RunPositionStore runPositionStore;

    private final ApprovalPreviewCache previewCache;

    private final RunGenerationService runGenerationService;

    private final Clock clock;

    /**
     * DB 를 비우고 스키마·시드를 다시 적재한 뒤 위치 캐시와 승인 미리보기 캐시를 지우고 <b>내일</b> 회차를 만든다.
     *
     * <p>내일 회차를 만드는 이유는 스테이징 초기화 버튼 뒤에도 학부모 "내일" 변경·탑승 끄기를 시험할 수 있어야
     * 해서다(Ruling 367 ④) — 기동 보충({@code DailyRunStartupCatchUp})은 기동 때 한 번뿐이라 초기화는 채워 주지 않는다.
     * <b>오늘 회차는 만들지 않는다</b> — 웹·Flutter 실서버 계약 시험이 초기화 직후의 시드 상태에 기댄다. 생성은 캐시를
     * 다 지운 <b>뒤에</b> 해서, 스케줄 하나가 실패해 예외가 올라와도 캐시 정리는 끝나 있다.
     *
     * @return 지운 위치 캐시 키 개수 — 되돌린 사실을 호출자가 눈으로 확인할 수 있게 한다
     */
    public int reset() {
        migrateRetryingOnDeadlock();
        previewCache.clear();
        int clearedPositionKeys = runPositionStore.deleteAll();
        runGenerationService.generate(LocalDate.now(clock).plusDays(1));
        return clearedPositionKeys;
    }

    /**
     * 시드 재적재를 하되 교착으로 중단되면 처음부터 다시 한다(R38-B).
     *
     * <p>{@code clean()} 이 테이블을 지우는 동안 배치·요청 트랜잭션이 같은 테이블을 잡고 있으면 Postgres 가 교착을
     * 감지해 한쪽을 중단시키는데, 그 희생자가 {@code DROP TABLE academy CASCADE} 면 첫 호출이 {@code 500} 이 된다.
     * 스케줄러를 멈춰도 요청 트랜잭션은 남으므로 잠그지 않고, {@code clean()} 이 몇 번을 해도 같은 결과라는 점에 기대 재시도한다.
     */
    private void migrateRetryingOnDeadlock() {
        for (int attempt = 1; ; attempt++) {
            try {
                migrationStrategy.migrate(flyway);
                return;
            } catch (RuntimeException e) {
                if (attempt >= MAX_MIGRATE_ATTEMPTS || !isDeadlock(e)) {
                    throw e;
                }
                log.warn("[dev-reset] 교착으로 중단돼 다시 시도한다 ({}/{})", attempt, MAX_MIGRATE_ATTEMPTS);
            }
        }
    }

    private static boolean isDeadlock(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && DEADLOCK_SQL_STATE.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
