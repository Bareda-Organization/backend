package src.backend.student.photo.spec;

/**
 * 학생 사진 저장 포트(§7 규칙 12 교체 축) — 로컬 디스크 · S3 · 외부 CDN 이 서로 다른 저장소라
 * 호출부는 이 인터페이스만 안다.
 *
 * <p>운영 저장 위치는 아직 미정이다(오픈 이슈 W) — 포트를 먼저 두면 정해질 때 구현체 추가와
 * 설정값 한 줄로 끝나고, 호출부는 손대지 않는다(ARCHITECTURE §3.2.1).
 */
public interface PhotoStorage {

    /**
     * 사진을 저장하고 응답·컬럼에 실을 주소를 만들어 준다.
     *
     * <p>요청의 {@code photo} 와 응답의 {@code photo_url} 이 <b>다른 값</b>인 자리가 여기다
     * (Ruling 160) — 주소를 만드는 것은 서버이고, 클라이언트가 준 문자열을 그대로 쓰는 경로는 부재하다.
     *
     * @return {@code student.photo_url} 에 저장할 주소
     * @throws RuntimeException 저장 실패. 호출부의 트랜잭션이 함께 되돌아가 학생 행이 남지 않는다
     */
    String store(StudentPhoto photo);

    /**
     * 저장했던 사진을 지운다 — 사진 교체 뒤의 옛 파일과, 커밋되지 못한 트랜잭션이 남긴 파일이 대상이다.
     *
     * <p>존재하지 않는 주소를 받아도 예외를 던지지 않는다. 이 호출은 트랜잭션이 끝난 뒤에 일어나
     * 되돌릴 대상이 이미 부재하며, 여기서 던지면 정상 종료한 요청이 실패로 뒤바뀐다.
     */
    void delete(String photoUrl);
}
