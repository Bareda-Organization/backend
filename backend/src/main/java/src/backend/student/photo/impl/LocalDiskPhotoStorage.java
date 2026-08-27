package src.backend.student.photo.impl;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import src.backend.student.photo.spec.PhotoStorage;
import src.backend.student.photo.spec.StudentPhoto;

/**
 * 사진을 서버 로컬 디스크에 두는 기본 구현 — 로컬·데모용이다.
 *
 * <p>{@code app.photo.storage} 가 없으면 이 구현이 뜬다({@code matchIfMissing}) — 운영 저장 위치가
 * 정해지면(오픈 이슈 W) 그 값 하나로 갈아끼우고 호출부는 손대지 않는다.
 *
 * <p>저장 위치와 주소 접두사는 설정으로 받는다. 이쪽은 <b>환경마다 다른 값</b>이라 코드 상수로 두면
 * 배포마다 코드를 고쳐야 한다 — 사양이 정한 정책값(5MB · 이미지 3종)이 {@link StudentPhoto} 의 코드
 * 상수인 것과 반대 이유다(횡단 규칙 10).
 */
@Component
@ConditionalOnProperty(name = "app.photo.storage", havingValue = "local", matchIfMissing = true)
public class LocalDiskPhotoStorage implements PhotoStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalDiskPhotoStorage.class);

    private final Path root;

    private final String urlPrefix;

    public LocalDiskPhotoStorage(@Value("${app.photo.local.root}") String root,
            @Value("${app.photo.local.url-prefix}") String urlPrefix) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.urlPrefix = urlPrefix;
    }

    /**
     * 파일명을 <b>서버가</b> 짓는다 — 올라온 이름을 쓰면 요청자가 경로를 정하게 되고, 같은 이름을 두 번
     * 올린 다른 학생의 사진이 서로를 덮는다.
     */
    @Override
    public String store(StudentPhoto photo) {
        String fileName = UUID.randomUUID() + "." + photo.extension();
        try {
            Files.createDirectories(root);
            Files.write(root.resolve(fileName), photo.content());
        } catch (IOException e) {
            throw new UncheckedIOException("학생 사진을 저장하지 못했습니다", e);
        }
        return urlPrefix + "/" + fileName;
    }

    /**
     * 이 구현이 만든 주소만 지운다 — 접두사가 다르거나 파일명에 경로 구분자가 섞인 값은 무시한다.
     *
     * <p>{@code photo_url} 은 지난 배포의 다른 구현체가 만들었을 수도, 손으로 넣은 값일 수도 있다.
     * 그것을 그대로 {@code resolve} 하면 {@code ../../} 한 줄로 저장 디렉터리 밖의 파일이 지워진다.
     */
    @Override
    public void delete(String photoUrl) {
        String fileName = fileNameOf(photoUrl);
        if (fileName == null) {
            return;
        }
        try {
            Files.deleteIfExists(root.resolve(fileName));
        } catch (IOException e) {
            log.warn("[photo] 옛 사진 파일 삭제 실패 url={}", photoUrl, e);
        }
    }

    private String fileNameOf(String photoUrl) {
        if (photoUrl == null || !photoUrl.startsWith(urlPrefix + "/")) {
            return null;
        }
        String fileName = photoUrl.substring(urlPrefix.length() + 1);
        boolean traversal = fileName.isBlank() || fileName.contains("/") || fileName.contains("\\")
                || fileName.contains("..");
        return traversal ? null : fileName;
    }
}
