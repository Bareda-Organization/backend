package src.backend.global.websocket;

/**
 * 커밋 뒤 방송 리스너가 방송 실행기에 넘기는 작업 — 이벤트 종류와 회차 id 를 달아 두어, 작업이 예외로 끝났을 때 실행기의 로그가 어느 방송인지 말해 준다.
 */
public record BroadcastTask(String event, Long runId, Runnable body) implements Runnable {

    @Override
    public void run() {
        body.run();
    }
}
