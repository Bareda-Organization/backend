package src.backend.student.command;

import java.util.Locale;
import java.util.function.Supplier;

import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.request.Patch;
import src.backend.student.dto.StudentRegisterRequest;
import src.backend.student.dto.StudentUpdateRequest;
import src.backend.student.entity.Gender;
import src.backend.student.entity.Student;
import src.backend.student.entity.StudentProfile;

/**
 * 등록 · 수정 요청을 {@link StudentProfile} 로 옮긴다 — 사진 저장은 호출부가 넘긴 공급자로 받아 <b>원래 자리의 순서</b>(이름 검사 뒤 · 성별 해석 앞)에서 부른다.
 * 성별이 잘못된 요청은 사진을 저장한 뒤에 거부되고, 롤백이 그 파일을 지운다.
 */
final class StudentProfiles {

    private StudentProfiles() {
    }

    /** 등록 요청의 프로필 — 선택 문자 항목의 빈 값은 값이 없는 것으로 저장한다. */
    static StudentProfile forRegistration(StudentRegisterRequest request, Supplier<String> photoUrl) {
        return new StudentProfile(request.name(), blankToNull(request.studentPhone()), photoUrl.get(),
                parseGender(request.gender()), request.birthDate(), blankToNull(request.grade()),
                blankToNull(request.className()), blankToNull(request.note()), request.canGoAlone());
    }

    /** 수정 요청의 프로필 — 보낸 항목만 새 값이고 나머지는 학생의 현재 값이다. */
    static StudentProfile forUpdate(StudentUpdateRequest request, Student student, Supplier<String> photoUrl) {
        return new StudentProfile(Patch.required(request.name(), student.getName()),
                Patch.optional(request.studentPhone(), student.getStudentPhone()), photoUrl.get(),
                genderOf(request, student), Patch.optional(request.birthDate(), student.getBirthDate()),
                Patch.optional(request.grade(), student.getGrade()),
                Patch.optional(request.className(), student.getClassName()),
                Patch.optional(request.note(), student.getNote()),
                Patch.required(request.canGoAlone(), student.isCanGoAlone()));
    }

    /** 선택 문자 항목의 빈 문자열·공백은 값이 없는 것으로 저장한다 — 수정(PATCH)이 빈 값을 지우는 것과 같은 규칙이다. */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 성별은 문자열로 받아 {@link Gender} 로 옮기므로 {@link Patch#optional} 을 못 쓴다 — 키 없음·지움·값을 여기서 가른다. */
    private static Gender genderOf(StudentUpdateRequest request, Student student) {
        if (request.gender() == null) {
            return student.getGender();
        }
        return parseGender(request.gender().value());
    }

    /** 값을 주지 않으면 {@code null}(= 바꾸지 않음)이고, 사양에 없는 값은 조용히 무시하지 않고 422 로 거부한다. */
    private static Gender parseGender(String gender) {
        if (gender == null || gender.isBlank()) {
            return null;
        }
        try {
            return Gender.valueOf(gender.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }
}
