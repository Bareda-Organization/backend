package testsupport.fork;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 시험 JVM(포크)마다 DB 와 사진 폴더가 갈린다 — 포크끼리 같은 행 · 같은 파일을 밟지 않는다. */
class ForkIsolationTest {

    @Test
    void 전용_DB_이름_뒤에_포크_번호를_붙인다() {
        assertThat(ForkIsolation.forkUrl("jdbc:postgresql://localhost:15432/sbtest_1_2", "3"))
                .isEqualTo("jdbc:postgresql://localhost:15432/sbtest_1_2_w3");
        assertThat(ForkIsolation.forkUrl("jdbc:postgresql://localhost:15432/sbtest?currentSchema=x", "1"))
                .isEqualTo("jdbc:postgresql://localhost:15432/sbtest_w1?currentSchema=x");
    }

    @Test
    void 관리_연결은_postgres_DB_로_간다() {
        assertThat(ForkIsolation.adminUrl("jdbc:postgresql://localhost:15432/sbtest_1_2?x=y"))
                .isEqualTo("jdbc:postgresql://localhost:15432/postgres?x=y");
    }

    @Test
    void 그레이들_워커_안에서는_이_JVM_의_DB_와_사진_폴더가_포크_번호를_가진다() {
        String worker = System.getProperty("org.gradle.test.worker");
        if (worker == null) {
            return; // IDE 등 그레이들 밖 실행은 격리하지 않는다
        }
        assertThat(System.getProperty("spring.datasource.url")).as("이 JVM 의 DB").endsWith("_w" + worker);
        assertThat(System.getProperty("app.photo.local.root")).as("이 JVM 의 사진 폴더").endsWith("/w" + worker);
    }
}
