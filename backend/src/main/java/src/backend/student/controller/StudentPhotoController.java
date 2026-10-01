package src.backend.student.controller;

import java.time.Duration;

import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import src.backend.global.config.ApiTags;
import src.backend.global.security.AuthUser;
import src.backend.global.security.authz.CanReadStudentPhoto;
import src.backend.student.query.StudentPhotoQueryService;
import src.backend.student.query.StudentPhotoQueryService.PhotoFile;

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

    private static final Duration CACHE_MAX_AGE = Duration.ofDays(1);

    private final StudentPhotoQueryService studentPhotoQueryService;

    /**
     * 사진 파일을 내려준다 — 공유 캐시에 남지 않도록 {@code Cache-Control: private} 이고, 하루({@code max-age=86400}) 동안 브라우저·앱이 다시
     * 받지 않는다. 파일명이 서버가 지은 UUID 라 같은 주소의 내용이 바뀌지 않으므로(사진을 바꾸면 새 주소) 파일명이 곧 {@code ETag} 이고,
     * {@code If-None-Match} 가 일치하면 Spring 이 본문 없이 {@code 304} 로 답한다(R46-KFIXBE K-3). 학원 소속 확인은 이 응답을 만들기 전에
     * 끝나 있어 {@code 304} 도 접근 권한을 우회하지 못한다. 본문은 파일에서 흘려 보내 힙에 올리지 않는다.
     */
    @CanReadStudentPhoto
    @Operation(summary = "학생 사진 파일 (STU-01, §5.11.1)", responses = @ApiResponse(responseCode = "200",
            content = @Content(mediaType = "image/*",
                    schema = @Schema(type = "string", format = "binary", example = "(이미지 바이너리 — png · jpeg · webp)"))))
    @GetMapping("/{fileName}")
    public ResponseEntity<Resource> photo(@AuthenticationPrincipal AuthUser authUser,
            @PathVariable("fileName") String fileName) {
        PhotoFile photo = studentPhotoQueryService.read(authUser, fileName);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(CACHE_MAX_AGE).cachePrivate())
                .eTag("\"" + fileName + "\"")
                .contentType(MediaType.parseMediaType(photo.contentType()))
                .body(photo.content());
    }
}
