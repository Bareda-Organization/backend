package src.backend.audit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * 감사 행을 쓰는 길은 하나다 — 도메인 모듈은 {@code AuditRecorder#record} 로만 남기고 {@code AuditLogRepository} 에 직접 저장하지 않는다. 행 내용은 어느 길로 써도 같아서
 * 실행 시험으로는 새 직접 저장이 들어와도 잡히지 않으므로 소스 구조로 지킨다. 읽기(조회 서비스 · 대시보드 집계)와 보존 정리의 삭제는 저장이 아니라서 대상 밖이다.
 *
 * <p>상대 경로라 <b>작업 디렉토리가 {@code backend/} 일 때만</b> 성립한다 — 어긋나면 수집이 공집합이 되어 첫 시험이 먼저 실패한다.
 */
class AuditWritePathConventionTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java/src/backend");

    private static final Pattern REPOSITORY_SAVE = Pattern.compile("(?i)auditLogRepository\\s*\\.\\s*(save|saveAll|saveAndFlush)\\s*\\(");

    @Test
    void 프로덕션_소스가_실제로_수집된다() {
        assertThat(프로덕션_소스()).as("하나도 못 찾으면 아래 단언은 무엇도 보장하지 않는다").isNotEmpty();
    }

    @Test
    void audit_밖의_코드는_감사_저장소에_직접_저장하지_않는다() {
        List<String> violations = 프로덕션_소스().stream()
                .filter(source -> !SOURCE_ROOT.relativize(source).startsWith("audit"))
                .filter(source -> REPOSITORY_SAVE.matcher(본문(source)).find())
                .map(source -> SOURCE_ROOT.relativize(source).toString())
                .toList();

        assertThat(violations).as("감사 행은 AuditRecorder#record 로 남긴다 — 직접 저장이 늘면 같은 트랜잭션 · 실패 처리 규칙이 곳곳에 따로 생긴다").isEmpty();
    }

    @Test
    void 감사_저장소를_주입받는_audit_밖의_클래스는_command_와_service_에_없다() {
        List<String> violations = 프로덕션_소스().stream()
                .filter(source -> !SOURCE_ROOT.relativize(source).startsWith("audit"))
                .filter(source -> {
                    String relative = SOURCE_ROOT.relativize(source).toString();
                    return relative.contains("/command/") || relative.contains("/service/");
                })
                .filter(source -> 본문(source).contains("AuditLogRepository"))
                .map(source -> SOURCE_ROOT.relativize(source).toString())
                .toList();

        assertThat(violations).isEmpty();
    }

    private static List<Path> 프로덕션_소스() {
        try (Stream<Path> paths = Files.walk(SOURCE_ROOT)) {
            return paths.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 주석을 걷어낸 코드 본문 — 자바독이 저장소 이름을 언급해도 걸리지 않게 한다. */
    private static String 본문(Path source) {
        try {
            return Files.readString(source).replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
