package src.backend.academy.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * BR-380 — 학원 연락처(선택 항목)를 빈 문자열·공백으로 보내면 {@code null}(미등록)로 저장한다. {@code ''} 로 남으면
 * {@code GET /auth/signup-status} 가 {@code "academy_contact": ""} 를 실어 클라이언트의 "등록된 문의처 없음" 대체 문구
 * (Ruling 781 — {@code null} 일 때만)가 뜨지 않고 빈칸이 보인다.
 */
class AcademyBlankContactTest {

    private Academy 학원() {
        return Academy.register("A001", "테스트학원", "서울", "테스트로 1", "032-000-0000");
    }

    @Test
    void 빈_연락처로_수정하면_연락처를_지운다() {
        Academy academy = 학원();

        academy.update(new AcademyProfile(null, null, null, "  ", null));

        assertThat(academy.getContact()).isNull();
    }

    @Test
    void 연락처를_보내지_않으면_그대로_둔다() {
        Academy academy = 학원();

        academy.update(new AcademyProfile(null, null, null, null, null));

        assertThat(academy.getContact()).isEqualTo("032-000-0000");
    }

    @Test
    void 빈_연락처로_등록하면_미등록이다() {
        Academy academy = Academy.register("A002", "테스트학원2", "서울", "테스트로 2", "");

        assertThat(academy.getContact()).isNull();
    }
}
