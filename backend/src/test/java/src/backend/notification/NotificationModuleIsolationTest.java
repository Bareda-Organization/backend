package src.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Phase 4 목표 5 — {@code notification} 은 <b>이벤트 구독으로만</b> 동작하고 다른 모듈이 직접 부르지
 * 않는다(§7 규칙 17 · ARCHITECTURE §3.3).
 *
 * <p>실행 단언으로는 잡히지 않는 축이다. 적재를 직접 호출로 해 둔 구현도 목표 1~4 를 전부 통과한다 —
 * 갈리는 것은 <b>동작이 아니라 구조</b>이고, 그 구조가 무너지면 푸시 발송이 승인 트랜잭션 안으로
 * 들어와 <b>발송 실패가 승인을 롤백</b>시킨다. 그 사고는 실 채널이 죽은 날에만 드러난다.
 *
 * <p>반대 방향({@code notification} 이 발행측 모듈의 이벤트 타입을 아는 것)은 금지 대상이 아니다 —
 * 구독자가 발행측의 계약을 아는 것은 의존 방향이 맞고, 규칙 17 이 막는 것은 <b>발행측이 알림을
 * 부르는 것</b>이다.
 *
 * <p>{@code import} 문이 아니라 <b>정규화된 이름 전체</b>를 찾는 이유는 {@code import} 없이
 * {@code src.backend.notification.command.NotificationOutbox} 라고 적으면 검사가 통째로 비켜 가기
 * 때문이다.
 */
class NotificationModuleIsolationTest {

    /** 상대 경로라 <b>작업 디렉토리가 {@code backend/} 일 때만</b> 성립한다 — 어긋나면 아래 수집이 공집합이 된다. */
    private static final Path SOURCE_ROOT = Path.of("src/main/java/src/backend");

    /** 이 문자열이 프로덕션 소스에 나타나면 그 파일은 알림 모듈을 안다. */
    private static final String NOTIFICATION_PACKAGE = "src.backend.notification";

    /**
     * 알림 모듈을 참조해도 되는 자리 — 모듈 자신과 {@code global} 뿐이다.
     *
     * <p>{@code global} 을 여는 이유는 그곳이 도메인 모듈이 아니라 <b>전 모듈이 공유하는 배선</b>
     * (설정 · 예외 · 응답 규약)이기 때문이다. 다만 지금은 실제 참조가 부재하며, 생긴다면 그것은
     * "왜 배선이 알림을 알아야 하는가" 를 다시 물어야 할 신호다.
     */
    private static final List<String> ALLOWED_PACKAGE_PATHS = List.of("notification", "global");

    @Test
    void 프로덕션_소스가_실제로_수집된다() {
        assertThat(프로덕션_소스())
                .as("하나도 못 찾으면 아래 단언은 무엇도 보장하지 않는다 — 작업 디렉토리나 패키지 배치가 어긋난 것")
                .isNotEmpty();
    }

    @Test
    void notification_모듈을_다른_프로덕션_모듈이_import_하지_않는다() {
        List<String> violations = 프로덕션_소스().stream()
                .filter(source -> !허용된_자리(source))
                .filter(source -> 본문(source).contains(NOTIFICATION_PACKAGE))
                .map(source -> SOURCE_ROOT.relativize(source).toString())
                .toList();

        assertThat(violations)
                .as("직접 호출이면 승인 트랜잭션 안에서 알림이 돌아 푸시 실패가 승인을 롤백시킨다 — "
                        + "발행측은 ApplicationEventPublisher 로 도메인 이벤트만 던져야 한다")
                .isEmpty();
    }

    /** {@code notification} · {@code global} 아래인가 — 경로의 <b>첫 마디</b>로 가른다. */
    private boolean 허용된_자리(Path source) {
        String first = SOURCE_ROOT.relativize(source).getName(0).toString();
        return ALLOWED_PACKAGE_PATHS.contains(first);
    }

    private List<Path> 프로덕션_소스() {
        try (Stream<Path> paths = Files.walk(SOURCE_ROOT)) {
            return paths.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String 본문(Path source) {
        try {
            return Files.readString(source);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
