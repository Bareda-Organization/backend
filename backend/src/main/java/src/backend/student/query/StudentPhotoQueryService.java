package src.backend.student.query;

import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.global.security.access.AcademyScope;
import src.backend.student.photo.spec.PhotoStorage;
import src.backend.student.photo.spec.StudentPhoto;
import src.backend.student.repository.StudentRepository;

/**
 * 학생 사진 파일 조회(API_SPEC §5.11.1 · Ruling 377) — 요청자 학원의 재학생 사진만 내려준다.
 *
 * <p>파일이 없는 것 · 남의 학원 것 · 퇴원생 것을 <b>모두 {@code 404 STUDENT_NOT_FOUND}</b> 로 답한다 —
 * 구분해 답하면 파일명(UUID)이 다른 학원에 존재하는지가 응답에서 드러난다.
 */
@Service
@RequiredArgsConstructor
public class StudentPhotoQueryService {

    private final StudentRepository studentRepository;

    private final PhotoStorage photoStorage;

    /** 사진 파일 핸들과 응답 형식 — 본문은 응답으로 흘려 보낼 때 열린다(R46-KFIXBE K-3). */
    public record PhotoFile(Resource content, String contentType) {
    }

    /** 사진 파일 핸들과 형식을 돌려준다 — 학원을 특정하지 못하는 요청자(학원 미지정 메인 관리자)는 거부한다. */
    public PhotoFile read(AuthUser requester, String fileName) {
        Long academyId = AcademyScope.resolveListScope(requester, null)
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
        if (!studentRepository.existsByPhotoUrlEndingWithAndAcademyIdAndDeletedAtIsNull("/" + fileName, academyId)) {
            throw new BusinessException(ErrorCode.STUDENT_NOT_FOUND);
        }
        Resource content = photoStorage.read(fileName)
                .orElseThrow(() -> new BusinessException(ErrorCode.STUDENT_NOT_FOUND));
        return new PhotoFile(content, StudentPhoto.contentTypeOf(extensionOf(fileName)));
    }

    private static String extensionOf(String fileName) {
        return fileName.substring(fileName.lastIndexOf('.') + 1);
    }
}
