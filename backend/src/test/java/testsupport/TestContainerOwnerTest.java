package testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 시험 컨테이너 주인 이름이 Docker 이름 규칙에 맞게 다듬어지고, 값이 없으면 {@code unknown} 이 되는지. */
class TestContainerOwnerTest {

    /** Gradle 이 이 시험 JVM 에도 주인 값을 넣으므로(build.gradle) 비우고 시작해 끝나면 되돌린다. */
    private String original;

    @BeforeEach
    void clear() {
        original = System.getProperty(TestContainerOwner.OWNER_PROPERTY);
        System.clearProperty(TestContainerOwner.OWNER_PROPERTY);
    }

    @AfterEach
    void restore() {
        if (original == null) {
            System.clearProperty(TestContainerOwner.OWNER_PROPERTY);
        } else {
            System.setProperty(TestContainerOwner.OWNER_PROPERTY, original);
        }
    }

    @Test
    void Docker_이름에_못_쓰는_문자는_하이픈으로_바꾸고_앞의_기호는_뗀다() {
        System.setProperty(TestContainerOwner.OWNER_PROPERTY, "_r46 fix/tx");

        assertThat(TestContainerOwner.owner()).isEqualTo("r46-fix-tx");
    }

    @Test
    void 값이_없으면_unknown() {
        assertThat(TestContainerOwner.owner()).isEqualTo("unknown");
    }
}
