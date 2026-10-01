package src.backend.student.photo.spec;

import java.util.Optional;

import org.springframework.core.io.Resource;

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

    /**
     * 저장했던 사진 본문의 <b>핸들</b>을 연다(API_SPEC §5.11.1) — {@code fileName} 은 {@link #store} 가 만든 주소의 마지막 조각이다.
     *
     * <p>본문 바이트를 여기서 읽지 않는다 — 호출부가 응답으로 흘려 보낼 때 열린다(R46-KFIXBE K-3). 사진 한 장 약 4MB 를 요청마다 힙에
     * 올리면 느린 클라이언트 200개가 힙 1GB 를 채웠다(R46 leak 실측).
     *
     * <p>파일이 없거나 저장 영역 밖을 가리키는 이름(경로 구분자·{@code ..})이면 {@link Optional#empty()} 다 —
     * 호출부는 둘을 "없음" 으로 똑같이 다룬다.
     */
    Optional<Resource> read(String fileName);
}
