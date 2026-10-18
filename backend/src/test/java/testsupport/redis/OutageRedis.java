package testsupport.redis;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import testsupport.TestContainerOwner;

/**
 * 멈추고 얼리고 다시 켤 수 있는 <b>이 시험 클래스 전용</b> Redis — Redis 장애 대체 경로 시험이 쓴다.
 *
 * <p>JVM 이 공유하는 {@link RedisTestContainerBase#REDIS} 를 멈추거나 얼리면 같은 JVM 의 다른 시험 클래스가 응답을 기다리며 멈춘다.
 * 그래서 장애를 내는 시험은 이 컨테이너를 따로 띄우고 {@code @AfterAll} 에서 {@link #close()} 로 지운다.
 * 호스트 포트는 미리 비어 있는 포트로 고정한다 — 컨테이너를 멈췄다 켜도 포트가 바뀌지 않아 이미 만든 연결 팩터리가 같은 주소로 다시 붙는다.
 *
 * <p>두 가지 장애를 낸다 — {@link #stop()} 은 프로세스가 사라진 Redis(연결 거부), {@link #pause()} 는 연결은 살아 있는데 답이 없는
 * Redis({@code docker pause}, 연결 거부보다 나쁜 쪽이라 명령 시간 상한이 없으면 호출자가 그대로 묶인다).
 */
public final class OutageRedis implements AutoCloseable {

    private static final int REDIS_PORT = 6379;

    private final GenericContainer<?> container;
    private final int hostPort;

    private OutageRedis(GenericContainer<?> container, int hostPort) {
        this.container = container;
        this.hostPort = hostPort;
    }

    /** 새 컨테이너를 띄운다. */
    public static OutageRedis start() {
        int port = freePort();
        GenericContainer<?> redis = TestContainerOwner.named(
                new GenericContainer<>(DockerImageName.parse("redis:8")).withExposedPorts(REDIS_PORT), "redis-outage");
        redis.setPortBindings(List.of(port + ":" + REDIS_PORT));
        redis.start();
        return new OutageRedis(redis, port);
    }

    public String host() {
        return container.getHost();
    }

    public int port() {
        return hostPort;
    }

    /** 프로세스를 멈춘다 — 이후 연결은 거부된다. */
    public void stop() {
        container.getDockerClient().stopContainerCmd(container.getContainerId()).withTimeout(0).exec();
    }

    /** 멈춘 컨테이너를 같은 포트로 다시 켠다 — 데이터는 비어 있다. */
    public void restart() {
        container.getDockerClient().startContainerCmd(container.getContainerId()).exec();
    }

    /** 얼린다 — 연결은 유지되고 응답만 오지 않는다. */
    public void pause() {
        container.getDockerClient().pauseContainerCmd(container.getContainerId()).exec();
    }

    public void unpause() {
        container.getDockerClient().unpauseContainerCmd(container.getContainerId()).exec();
    }

    @Override
    public void close() {
        container.stop();
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("비어 있는 포트를 찾지 못했다", e);
        }
    }
}
