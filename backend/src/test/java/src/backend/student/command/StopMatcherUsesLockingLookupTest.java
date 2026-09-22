package src.backend.student.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Ruling 179 의 잠금장치 — <b>승하차지를 만드는 경로는 잠금 있는 후보 조회만 쓴다.</b>
 *
 * <p>2026-09-22 에 조회 전용 {@code StopRepository.findNearby}(잠금 없음)가 주소 검색용으로 생겼다.
 * 그 자체는 아무것도 만들지 않아 안전하지만, <b>병합 경로가 실수로 그쪽을 부르면</b> 동시 요청 둘이
 * 서로의 미커밋 INSERT 를 못 본 채 같은 자리에 승하차지를 둘 만든다 — 그리고 그것은 동시 요청에서만
 * 드러나 평소 테스트로는 잡히지 않는다. 그래서 <b>호출 대상 자체</b>를 소스에서 고정한다.
 */
class StopMatcherUsesLockingLookupTest {

    private static final Path MATCHER = Path.of("src/main/java/src/backend/student/command/StopMatcher.java");

    @Test
    void 승하차지를_만드는_경로는_잠금_없는_근접_조회를_쓰지_않는다() throws IOException {
        String source = Files.readString(MATCHER);

        assertThat(source)
                .as("StopMatcher 는 %s 다 — 경로가 바뀌었으면 이 시험의 상수를 고친다", MATCHER)
                .contains("matchOrCreate");
        assertThat(source)
                .as("병합은 학원을 잠근 뒤에 후보를 읽어야 한다(Ruling 179) — findNearby 는 조회 전용이다")
                .doesNotContain("findNearby")
                .contains("lockAcademyAndFindNearby");
    }
}
