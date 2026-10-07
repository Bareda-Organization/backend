package src.backend.global.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * postgres·redis 가 개발·운영·스테이징 compose 와 시험용 컨테이너(Testcontainers)에서 같은 주 버전인지 지키는 가드(Ruling 729).
 *
 * <p>한 곳만 올리면 시험은 옛 버전 위에서 초록인데 운영은 새 버전이거나 그 반대다 — 주 버전이 다르면 SQL 동작·기본 설정·데이터 형식이
 * 다르다. 특히 postgres 18 부터는 공식 이미지의 데이터 위치가 {@code /var/lib/postgresql/18/docker} 로 바뀌어, 옛 위치
 * {@code /var/lib/postgresql/data} 에 마운트하면 컨테이너가 시작하지 않는다 — 이미지 태그만 올리고 마운트를 두면 운영 배포에서만 드러난다.
 *
 * <p>Dependabot 이 postgres 주 버전 상향 PR 을 만들지 못하게 막았는지도 본다 — BR-330 (R10-06). 위 시험은 "주 버전이 서로 같은가" 만 보므로 사람이 compose 와
 * 시험 컨테이너를 같이 19 로 올리면 통과하는데, 이미지 안에서 데이터 위치가 주 버전 하위({@code 19/docker})로 바뀌어 기존 볼륨(18 데이터)을 못 읽고
 * 빈 DB 로 뜬다 — 헬스는 UP 이고 매시 백업이 빈 DB 를 올려 정상 덤프가 7일 뒤 사라진다. 주 버전 상향은 {@code pg_upgrade} 절차로 사람이 한다.
 *
 * <p>파일 텍스트만 읽으므로 Docker·DB 없이 실행된다. 주석 줄은 걷어 내고 본다.
 */
class RuntimeImageParityTest {

    /** 빈 데이터 디렉터리를 만들 때 DB 기본 정렬을 ICU 한국어로 — compose · Testcontainers 가 같은 문자열을 쓴다(`Ruling 851`). */
    private static final String KOREAN_INITDB_ARGS = "--locale-provider=icu --icu-locale=ko-KR";

    private static final Path ROOT = Path.of("..");

    private static final List<String> COMPOSE_FILES = List.of(
            "docker-compose.yml", "docker-compose.prod.yml", "docker-compose.staging.yml");

    /** 18 부터 공식 postgres 이미지의 볼륨 선언 위치가 {@code /var/lib/postgresql} 로 바뀌었다. */
    private static final int FIRST_MAJOR_WITH_VERSIONED_DATA_DIR = 18;

    @Test
    @DisplayName("postgres 주 버전이 compose 3개와 시험용 컨테이너에서 모두 같다")
    void postgresMajorIsSameEverywhere() throws IOException {
        Set<String> majors = majorsInCompose("postgres");
        majors.addAll(majorsInTestSources("new PostgreSQLContainer\\(\"postgres:(\\d+)"));
        assertThat(majors).as("compose(개발·운영·스테이징)와 Testcontainers 의 postgres 주 버전").hasSize(1);
    }

    @Test
    @DisplayName("redis 주 버전이 compose 3개와 시험용 컨테이너에서 모두 같다")
    void redisMajorIsSameEverywhere() throws IOException {
        Set<String> majors = majorsInCompose("redis");
        majors.addAll(majorsInTestSources("DockerImageName\\.parse\\(\"redis:(\\d+)"));
        assertThat(majors).as("compose(개발·운영·스테이징)와 Testcontainers 의 redis 주 버전").hasSize(1);
    }

    @Test
    @DisplayName("postgres 데이터 마운트 위치가 이미지 주 버전과 맞다 — 18 이상은 /var/lib/postgresql, 그 미만은 /var/lib/postgresql/data")
    void postgresDataMountMatchesImageMajor() throws IOException {
        int major = Integer.parseInt(majorsInCompose("postgres").iterator().next());
        String expectedSuffix = major >= FIRST_MAJOR_WITH_VERSIONED_DATA_DIR ? "" : "/data";
        for (String compose : List.of("docker-compose.prod.yml", "docker-compose.staging.yml")) {
            Matcher mount = Pattern.compile("(?m)^\\s*-\\s*(?:postgres-data:)?/var/lib/postgresql(/data)?\\s*$")
                    .matcher(stripComments(read(compose)));
            assertThat(mount.find()).as("%s 에 postgres 데이터 마운트(볼륨 또는 tmpfs)가 있어야 한다", compose).isTrue();
            String actualSuffix = mount.group(1) == null ? "" : mount.group(1);
            assertThat(actualSuffix).as("%s postgres %d 의 데이터 마운트 위치 끝부분", compose, major)
                    .isEqualTo(expectedSuffix);
        }
    }

    @Test
    @DisplayName("Dependabot 은 postgres 주 버전 상향 PR 을 만들지 않는다 — docker-compose 블록이 postgres 의 semver-major 를 무시한다")
    void dependabotNeverBumpsPostgresMajor() throws IOException {
        String dependabot = stripComments(read(".github/dependabot.yml"));
        String composeEcosystem = Stream.of(dependabot.split("(?m)^  - (?=package-ecosystem:)"))
                .filter(block -> block.startsWith("package-ecosystem: docker-compose")).findFirst().orElse("");

        assertThat(composeEcosystem).as("dependabot.yml 에 docker-compose 생태계 블록(운영 compose 의 postgres 이미지를 갱신 대상으로 본다)이 있어야 한다").isNotEmpty();
        assertThat(composeEcosystem).as("docker-compose 블록의 ignore — 주 버전 상향은 데이터 위치가 바뀌어 pg_upgrade 가 필요하다")
                .containsPattern("dependency-name:\\s*[\"']?postgres[\"']?\\s+update-types:\\s*\\[[^\\]]*version-update:semver-major");
    }

    /**
     * 한국어 정렬(ICU) — DB 기본 정렬 규칙이 이름순 목록 전부의 순서를 정한다. 공식 이미지 기본값(`en_US.utf8`)은 한글을
     * 가나다순으로 세우지 않아 "코스모스 → 한빛 → 그린" 처럼 섞였다(2026-10-07 · `Ruling 851`). 이 인자는 빈 데이터 디렉터리를 처음
     * 만들 때만 먹으므로, 한 곳에서라도 빠지면 그 환경만 조용히 다르게 정렬된다 — 운영 compose 를 시험하는 다른 수단이 없다.
     */
    @Test
    @DisplayName("postgres 를 만드는 곳(compose 3개 · 시험용 컨테이너) 전부가 한국어 정렬(ICU)로 DB 를 만든다")
    void postgresUsesKoreanCollationEverywhere() throws IOException {
        for (String compose : COMPOSE_FILES) {
            assertThat(stripComments(read(compose))).as("%s 의 postgres 환경 변수", compose)
                    .contains("POSTGRES_INITDB_ARGS: \"" + KOREAN_INITDB_ARGS + "\"");
        }
        try (Stream<Path> files = Files.walk(Path.of("src/test/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                if (source.contains("new PostgreSQLContainer(\"postgres:")) {
                    assertThat(source).as("%s 의 시험용 postgres 컨테이너", file)
                            .contains("withEnv(\"POSTGRES_INITDB_ARGS\", \"" + KOREAN_INITDB_ARGS + "\")");
                }
            }
        }
    }

    /** compose 파일마다 `image: <서비스>:<주>.…` 의 주 버전. 이미지 줄이 없는 파일은 건너뛴다 — 단 하나도 못 찾으면 실패한다. */
    private static Set<String> majorsInCompose(String image) throws IOException {
        Pattern pattern = Pattern.compile("(?m)^\\s*image:\\s*" + image + ":(\\d+)");
        Set<String> majors = new TreeSet<>();
        int found = 0;
        for (String compose : COMPOSE_FILES) {
            Matcher matcher = pattern.matcher(stripComments(read(compose)));
            while (matcher.find()) {
                majors.add(matcher.group(1));
                found++;
            }
        }
        assertThat(found).as("compose 3개에서 찾은 %s 이미지 줄 수", image).isEqualTo(COMPOSE_FILES.size());
        return majors;
    }

    /** 시험 소스 전체에서 정규식의 첫 그룹(주 버전)을 모은다 — 새 시험 컨테이너가 다른 버전을 쓰면 여기서 걸린다. */
    private static Set<String> majorsInTestSources(String regex) throws IOException {
        Pattern pattern = Pattern.compile(regex);
        Set<String> majors = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/test/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = pattern.matcher(Files.readString(file));
                while (matcher.find()) {
                    majors.add(matcher.group(1));
                }
            }
        }
        assertThat(majors).as("시험 소스에서 찾은 주 버전(%s)", regex).isNotEmpty();
        return majors;
    }

    private static String read(String relative) throws IOException {
        return Files.readString(ROOT.resolve(relative).normalize());
    }

    /** `#` 로 시작하는 줄(YAML 주석)을 걷어 낸다. */
    private static String stripComments(String text) {
        return text.lines().filter(line -> !line.strip().startsWith("#")).collect(Collectors.joining("\n"));
    }
}
