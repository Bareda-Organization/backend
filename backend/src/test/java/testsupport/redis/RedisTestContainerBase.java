package testsupport.redis;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import testsupport.TestContainerOwner;

/**
 * 전용 Redis 컨테이너로 {@code spring.data.redis.*} 를 갈아 끼우는 테스트 베이스(목표 3, Phase 10
 * T1) — 공유 컨테이너 {@code school-bus-redis-1} 은 병렬 좌석(T1·T3·T4)이 동시에 건드리므로 여기서는
 * 쓰지 않는다.
 *
 * <p>패키지를 {@code src.backend} 트리 밖({@code testsupport}) 에 둔 이유는
 * {@code testsupport.db.MigratedPostgresTestBase} 와 같다 — {@code BackendApplication} 의 기본
 * 컴포넌트 스캔 범위에 테스트 전용 클래스가 섞여 들어가는 것을 막기 위함이다.
 *
 * <p>Testcontainers 2.0.5 BOM 에는 Redis 전용 모듈이 없다(build.gradle 의 testImplementation 목록
 * 옆 주석 참고) — 그래서 전용 컨테이너 클래스 대신 {@link GenericContainer} 로 redis 이미지를 직접
 * 띄운다. {@code RedisConnectionFactory} 는 {@code spring.data.redis.host}·{@code port} 만 보고
 * 자동 구성되므로 그 두 값만 갈아 끼우면 충분하다. ⚠ 이 저장소에는 Redis 설정 클래스가 없다 —
 * 예전에 있던 {@code RedisConfig} 는 다형 직렬화 빈을 두었다가, 그 형식이 소비자 두 곳의 평문
 * 파서와 어긋나 위치 조회가 500 을 내는 결함을 만들어 삭제됐다. 되살리지 마라 — 값 형식은
 * {@code RunPositionRedisValue} 자바독이 계약으로 명시한다.
 *
 * <p>공유 컨테이너를 못 쓰는 이유가 하나 더 있다 — Postgres 는 {@code @Transactional} 롤백으로
 * 시험 간 격리되지만, Redis 는 트랜잭션이 없어 한 시험이 쓴 키가 다음 시험에 그대로 남는다. 이
 * 클래스 트리 전용 컨테이너를 쓰는 것이 그 격리를 대신한다 — 격리 대상은 "컨테이너 인스턴스" 가
 * 아니라 "키 이름" 이다. 각 시험이 쓰는 키는 {@code runId} 등 단조 증가 식별자를 접두사로 붙여
 * 만들어지므로(예: {@code RunPositionRedisValue} 사용부) 하위 클래스끼리 같은 키를 밟지 않는다.
 *
 * <p>이미지 태그는 {@code redis:8}(alpine 아님) — {@code docker-compose.yml} 의 운영 컨테이너와
 * 같은 태그다. 시험과 운영이 다른 이미지를 쓰면 버전 차이로 나는 결함을 시험이 못 본다.
 *
 * <p>쓰는 법 — 이 클래스를 상속하면 {@code @DynamicPropertySource} 가 함께 상속돼, 그 테스트의
 * {@code @SpringBootTest} 컨텍스트가 이 컨테이너에 연결된다.
 */
public abstract class RedisTestContainerBase {

    /**
     * JVM 포크 하나에 컨테이너 하나 — <b>모든 하위 테스트 클래스가 이 인스턴스를 나눠 쓴다.</b>
     *
     * <p>⚠ {@code @Testcontainers}·{@code @Container} 를 쓰지 않는다. 그 두 애너테이션은 JUnit 확장이
     * 컨테이너 수명을 <b>테스트 클래스 단위</b>로 관리하게 만든다 — 클래스가 끝날 때 {@code stop()},
     * 다음 클래스에서 {@code start()} 다. {@code static} 필드라고 해서 공유되지 않는다.
     *
     * <p>그 형태가 실제로 낸 결함(2026-09-14 실측) — {@code @DynamicPropertySource} 가 준 매핑 포트는
     * {@code LettuceConnectionFactory} 빈이 만들어질 때 <b>한 번</b> 읽혀 Spring 컨텍스트에 굳는다.
     * 그런데 그 컨텍스트는 설정이 같은 다음 클래스가 <b>캐시에서 그대로 재사용</b>한다. 그 사이
     * 컨테이너는 파기되고 새 컨테이너가 <b>다른 포트</b>로 뜨므로, 두 번째 클래스는 이미 사라진
     * 포트로 접속해 {@code SocketException: Connection reset} 을 맞는다. 아래가 실측한 회차다.
     *
     * <pre>
     * beforeAll  컨테이너 1ea7f1aaaf9d  포트 55079
     * 컨텍스트 생성 → LettuceConnectionFactory 가 55079 를 붙든다
     * 1번 클래스   컨테이너 포트 55079 = 컨텍스트 포트 55079  → 통과
     * afterAll   1ea7f1aaaf9d 파기
     * beforeAll  컨테이너 b6a383b80594  포트 55100  (새 포트)
     * 2번 클래스   컨테이너 포트 55100 ≠ 컨텍스트 포트 55079  → Connection reset
     * </pre>
     *
     * <p>단독 실행은 컨텍스트를 한 번만 만들어 늘 통과하고, 전체 실행에서만 드러난다. 재현율은
     * 두 클래스만 돌린 최소 묶음에서 <b>5회 중 4회</b>였다(통과한 1회는 파기된 포트를 Docker 가
     * 새 컨테이너에 다시 배정한 경우로 본다 — 이 묶음에는 컨텍스트 캐시 축출 압력이 부재하다).
     *
     * <p>그래서 정적 초기화 블록에서 직접 띄우고 <b>멈추지 않는다</b>(Testcontainers 가 문서화한
     * singleton container 방식). 회수는 Ryuk 사이드카가 JVM 종료 시점에 한다.
     */
    protected static final GenericContainer<?> REDIS = TestContainerOwner.named(new GenericContainer<>(
            DockerImageName.parse("redis:8"))
            .withExposedPorts(6379), "redis");

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
