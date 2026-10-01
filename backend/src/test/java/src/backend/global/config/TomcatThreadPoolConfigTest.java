package src.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.tomcat.autoconfigure.TomcatServerProperties;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * 운영·demo·스테이징의 Tomcat 요청 스레드 수가 <b>DB 연결 풀의 5배</b>로 명시돼 있다(R46-LATERBE L11, Ruling 674). 명시하지 않으면
 * 기본 200 이라, 풀이 마를 때 200개 스레드가 연결을 기다리며 서 있다(부하 측정: 풀 10 에서 {@code tomcatBusy} 200 · {@code hikariPend}
 * 116~194). 5배인 이유는 DB 를 안 쓰는 요청(Redis 읽기 · 헬스)이 연결 대기 스레드에 막히지 않을 여유를 두되 대기열이 풀 크기를 한참
 * 넘어 쌓이지 않게 하려는 것이다.
 *
 * <p>yml 을 프로파일 문서 단위로 읽어 {@link TomcatServerProperties}(실제 바인딩 대상)에 묶는다 — 키 이름이 틀리면 기본값 200 이
 * 남아 이 시험이 실패한다. 스테이징은 풀을 따로 정하지 않아 Hikari 기본 10 이다.
 */
class TomcatThreadPoolConfigTest {

    private static final int HIKARI_DEFAULT_POOL = 10;

    @ParameterizedTest
    @CsvSource({"prod, 20, 100", "demo, 20, 100", "staging, 10, 50"})
    @DisplayName("요청 스레드 수는 풀 크기의 5배로 명시돼 있고 대기열은 100 이다")
    void 요청_스레드_수가_연결_풀의_5배로_명시돼_있다(String profile, int expectedPool, int expectedThreads) throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        // 시험 JVM 의 시스템 속성(build.gradle 이 풀을 6 으로 줄여 둔다)이 yml 에 없는 값을 채우지 않게 뺀다
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(profileDocument(profile));
        Binder binder = Binder.get(environment);

        TomcatServerProperties tomcat = binder.bind("server.tomcat", Bindable.of(TomcatServerProperties.class))
                .orElseThrow(() -> new AssertionError(profile + " 문서에 server.tomcat 설정이 없다"));
        int pool = binder.bind("spring.datasource.hikari.maximum-pool-size", Integer.class).orElse(HIKARI_DEFAULT_POOL);

        assertThat(pool).as("%s 연결 풀", profile).isEqualTo(expectedPool);
        assertThat(tomcat.getThreads().getMax()).as("%s 요청 스레드 최대", profile).isEqualTo(expectedThreads)
                .isEqualTo(pool * 5);
        assertThat(tomcat.getAcceptCount()).as("%s 대기열(accept-count) — 스레드가 다 찬 뒤 TCP 대기열", profile).isEqualTo(100);
    }

    /** {@code application.yml} 의 여러 문서 중 {@code on-profile} 이 일치하는 문서 하나 — 공통 문서의 값은 섞지 않는다. */
    private PropertySource<?> profileDocument(String profile) throws Exception {
        for (PropertySource<?> document : new YamlPropertySourceLoader().load("application",
                new ClassPathResource("application.yml"))) {
            if (profile.equals(document.getProperty("spring.config.activate.on-profile"))) {
                return document;
            }
        }
        throw new AssertionError("프로파일 문서를 찾지 못했다: " + profile);
    }
}
