package src.backend.run.repository;

/** 회차별 지연 알림 수신 건수 한 행 — {@link DelayNoticeRepository#countRecipientsOfNotices} 가 돌려준다. */
public interface DelayRecipientCount {

    Long getRunId();

    long getRecipientCount();
}
