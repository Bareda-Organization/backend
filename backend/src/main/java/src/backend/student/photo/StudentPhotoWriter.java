package src.backend.student.photo;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;

import src.backend.student.photo.spec.PhotoStorage;
import src.backend.student.photo.spec.StudentPhoto;

/**
 * 사진 파일과 학생 행이 <b>같은 결말</b>을 맞도록 저장 시점을 트랜잭션에 붙인다(STU-02·03).
 *
 * <p>외부 저장소는 트랜잭션 밖이라 두 방향으로 어긋날 수 있고, 둘 다 조용하다.
 *
 * <table>
 *   <tr><th>어긋나는 방향</th><th>여기서 막는 방법</th></tr>
 *   <tr><td>파일은 저장됐는데 DB 가 롤백</td><td>롤백 시 그 파일을 지운다 — 아무도 가리키지 않는
 *       파일이 디스크에 쌓이는 것을 막는다</td></tr>
 *   <tr><td>DB 는 커밋됐는데 파일이 부재</td><td>저장을 <b>트랜잭션 안에서 먼저</b> 한다 — 저장이
 *       실패하면 예외가 그대로 올라가 학생 행이 남지 않는다</td></tr>
 * </table>
 *
 * <p>옛 파일 삭제만 <b>커밋 뒤</b>다. 트랜잭션 안에서 지우면 그 뒤 롤백됐을 때 행은 옛 주소를
 * 가리키는데 그 파일은 이미 사라진, 되돌릴 수 없는 상태가 된다.
 */
@Component
@RequiredArgsConstructor
public class StudentPhotoWriter {

    private final PhotoStorage photoStorage;

    /**
     * 사진을 저장하고 {@code photo_url} 을 돌려준다 — 사진이 없으면 {@code null} 이고, 그것이
     * 등록에서는 "사진 없음", 수정에서는 "바꾸지 않음" 이다.
     */
    public String store(StudentPhoto photo) {
        if (photo == null) {
            return null;
        }
        String photoUrl = photoStorage.store(photo);
        onRollback(() -> photoStorage.delete(photoUrl));
        return photoUrl;
    }

    /**
     * 사진을 바꾼다 — 새 파일을 저장하고 <b>커밋이 끝난 뒤</b> 옛 파일을 지운다.
     *
     * @param currentPhotoUrl 지금 행이 가리키는 주소. {@code null} 이면 지울 대상이 부재하다
     */
    public String replace(String currentPhotoUrl, StudentPhoto photo) {
        String photoUrl = store(photo);
        if (photoUrl != null && currentPhotoUrl != null) {
            afterCommit(() -> photoStorage.delete(currentPhotoUrl));
        }
        return photoUrl;
    }

    /**
     * 트랜잭션이 커밋되지 <b>못한</b> 경우에만 돌린다 — 동기화가 걸려 있지 않으면 되돌릴 트랜잭션
     * 자체가 부재하므로 그냥 넘어간다.
     */
    private void onRollback(Runnable action) {
        register(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    action.run();
                }
            }
        });
    }

    private void afterCommit(Runnable action) {
        register(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private void register(TransactionSynchronization synchronization) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(synchronization);
        }
    }
}
