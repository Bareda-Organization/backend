package testsupport.redis;

/**
 * 이 시험 JVM 의 전용 Redis 컨테이너({@link RedisTestContainerBase#REDIS})를 {@code docker pause} 로 얼린다 —
 * 연결은 살아 있는데 답만 오지 않는 Redis(W12-01)를 재현한다. 연결 거부보다 나쁜 쪽이라 명령 시간 상한이
 * 없으면 호출자가 그대로 묶인다.
 *
 * <p>컨테이너를 멈추지(stop) 않고 얼리는 이유 — 멈췄다 다시 켜면 매핑 포트가 바뀔 수 있어 캐시된 다른 시험
 * 컨텍스트가 사라진 포트를 붙든다({@link RedisTestContainerBase#REDIS} 자바독의 사고와 같은 형태). 얼렸다 풀면
 * 포트·연결이 그대로다. {@code docker compose} 의 공유 Redis 는 건드리지 않는다.
 *
 * <p>쓰는 법 — {@code try (RedisFreeze ignored = RedisFreeze.start()) { ... }}. 전용 컨테이너를 JVM 안 모든 시험이
 * 나눠 쓰므로 반드시 {@code try} 로 풀어 다음 시험에 얼린 채 넘기지 않는다.
 */
public final class RedisFreeze implements AutoCloseable {

    private RedisFreeze() {
    }

    /** 컨테이너를 얼린다. */
    public static RedisFreeze start() {
        RedisTestContainerBase.REDIS.getDockerClient()
                .pauseContainerCmd(RedisTestContainerBase.REDIS.getContainerId()).exec();
        return new RedisFreeze();
    }

    /** 얼린 컨테이너를 푼다. */
    @Override
    public void close() {
        RedisTestContainerBase.REDIS.getDockerClient()
                .unpauseContainerCmd(RedisTestContainerBase.REDIS.getContainerId()).exec();
    }
}
