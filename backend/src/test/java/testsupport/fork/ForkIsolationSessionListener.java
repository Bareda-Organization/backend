package testsupport.fork;

import java.sql.SQLException;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * JUnit 세션이 열릴 때(그레이들 워커 JVM 마다 한 번, 어떤 컨텍스트보다 먼저) 이 JVM 을 포크 전용 DB · 사진 폴더로 돌린다.
 * {@code META-INF/services} 로 등록돼 시험 클래스가 알 필요가 없다.
 */
public class ForkIsolationSessionListener implements LauncherSessionListener {

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        try {
            ForkIsolation.isolateThisJvm();
        } catch (SQLException e) {
            throw new IllegalStateException("포크 전용 DB 를 만들지 못했다 — 포크끼리 DB 를 나누지 못하면 시험이 서로의 행을 밟는다", e);
        }
    }
}
