package src.backend.student.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import src.backend.global.config.ApiTags;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanReadStudentPhoto;
import src.backend.student.photo.spec.StudentPhoto;
import src.backend.student.query.StudentPhotoQueryService;

/**
 * 학생 사진 파일 API(STU-01 {@code photo} · §4.2 {@code photo_url}, API_SPEC §5.11.1 · Ruling 377).
 *
 * <p>응답 본문이 이미지 바이너리라 {@code ApiResponse} 봉투를 쓰지 않는다. 무인증 정적 경로로 열지 않는다 —
 * 사진은 L3 다(FEATURE_SPEC §6.3).
 */
@Tag(name = ApiTags.STAFF)
@RestController
@RequestMapping("/files/photos")
@RequiredArgsConstructor
public class StudentPhotoController {

    private final StudentPhotoQueryService studentPhotoQueryService;

    /** 사진 파일을 내려준다 — 공유 캐시에 남지 않도록 {@code Cache-Control: private} 이다. */
    @CanReadStudentPhoto
    @Operation(summary = "학생 사진 파일 (STU-01, §5.11.1)")
    @GetMapping("/{fileName}")
    public ResponseEntity<byte[]> photo(@AuthenticationPrincipal AuthUser authUser,
            @PathVariable("fileName") String fileName) {
        StudentPhoto photo = studentPhotoQueryService.read(authUser, fileName);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.empty().cachePrivate())
                .contentType(MediaType.parseMediaType(photo.contentType()))
                .body(photo.content());
    }
}
