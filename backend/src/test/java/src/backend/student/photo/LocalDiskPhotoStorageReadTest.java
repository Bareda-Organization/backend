package src.backend.student.photo;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;

import src.backend.student.photo.impl.LocalDiskPhotoStorage;

/** 사진 읽기가 저장 디렉터리 밖을 열지 못하는지(BR-214 · API_SPEC §5.11.1 "파일명 경로 이탈 거부"). */
class LocalDiskPhotoStorageReadTest {

    @TempDir
    Path temp;

    @Test
    void 저장_디렉터리_밖의_파일은_경로_이탈_이름으로_읽히지_않는다() throws Exception {
        Path root = Files.createDirectory(temp.resolve("photos"));
        Files.write(temp.resolve("secret.txt"), new byte[] {1, 2, 3});
        Files.write(root.resolve("ok.png"), new byte[] {9});
        LocalDiskPhotoStorage storage = new LocalDiskPhotoStorage(root.toString(), "/api/v1/files/photos");

        assertThat(storage.read("ok.png").orElseThrow().getContentAsByteArray()).containsExactly(9);
        assertThat(storage.read("../secret.txt")).as("상위 디렉터리").isEmpty();
        assertThat(storage.read("..\\secret.txt")).as("역슬래시").isEmpty();
        assertThat(storage.read("sub/ok.png")).as("경로 구분자").isEmpty();
        assertThat(storage.read("..")).isEmpty();
    }

    /**
     * R46-KFIXBE K-3 — 읽기는 파일 <b>핸들</b>만 돌려주고 본문은 스트림을 열 때 읽는다. 사진 한 장(약 4MB)을 요청마다 힙에 올리면 느린 클라이언트
     * 200개가 힙 1GB 를 채웠다. 핸들을 받은 뒤 파일을 바꿔 보면 스트림이 그때의 내용을 읽는지로 본문이 미리 적재되지 않았음을 가른다.
     */
    @Test
    void 읽기는_본문을_미리_올리지_않고_스트림을_열_때_읽는다() throws Exception {
        Path root = Files.createDirectory(temp.resolve("photos"));
        Files.write(root.resolve("a.png"), new byte[] {1, 1, 1});
        LocalDiskPhotoStorage storage = new LocalDiskPhotoStorage(root.toString(), "/api/v1/files/photos");

        Resource handle = storage.read("a.png").orElseThrow();
        Files.write(root.resolve("a.png"), new byte[] {2, 2, 2, 2});

        assertThat(handle.getContentAsByteArray()).as("핸들을 받은 뒤 바뀐 파일을 스트림이 읽는다").containsExactly(2, 2, 2, 2);
    }
}
