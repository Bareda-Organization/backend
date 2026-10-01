package testsupport;

import java.util.UUID;

import org.testcontainers.containers.GenericContainer;

/**
 * 시험 컨테이너에 "누가 띄웠나" 를 이름과 라벨로 붙인다 — 여러 작업 창이 동시에 시험을 돌리면 Docker 화면에
 * 무작위 이름(`dreamy_murdock` 등)만 보여 어느 창의 것인지 가를 수 없었다(2026-10-01 사용자 요청).
 *
 * <p>주인 이름은 Gradle 이 {@code -PtestDbUrl} 의 DB 이름으로 넘긴다({@code testcontainers.owner}) — 그 인자는
 * 창마다 전용이고 빠뜨리면 빌드가 실패하므로(build.gradle) 따로 외울 값이 늘지 않는다. 이름은
 * {@code <주인>-<용도>-<6자>} — 같은 JVM 이 같은 용도 컨테이너를 둘 띄워도 겹치지 않게 뒤에 무작위 6자를 붙인다.
 * 라벨 {@code school-bus.owner} 로 {@code docker ps --filter label=school-bus.owner=<주인>} 을 할 수 있다.
 */
public final class TestContainerOwner {

    static final String OWNER_PROPERTY = "testcontainers.owner";
    static final String OWNER_LABEL = "school-bus.owner";
    private static final String UNKNOWN_OWNER = "unknown";

    private TestContainerOwner() {
    }

    /** {@code container} 에 주인 이름·라벨을 붙여 그대로 돌려준다 — 시작 전에 불러야 한다. */
    public static <T extends GenericContainer<?>> T named(T container, String purpose) {
        String owner = owner();
        String name = owner + "-" + purpose + "-" + UUID.randomUUID().toString().substring(0, 6);
        container.withLabel(OWNER_LABEL, owner);
        container.withCreateContainerCmdModifier(cmd -> cmd.withName(name));
        return container;
    }

    /** Docker 이름 규칙([a-zA-Z0-9][a-zA-Z0-9_.-]*)에 맞게 다듬은 주인 이름 — 값이 없으면 {@value #UNKNOWN_OWNER}. */
    static String owner() {
        String raw = System.getProperty(OWNER_PROPERTY, "");
        String cleaned = raw.replaceAll("[^a-zA-Z0-9_.-]", "-").replaceAll("^[^a-zA-Z0-9]+", "");
        return cleaned.isEmpty() ? UNKNOWN_OWNER : cleaned;
    }
}
