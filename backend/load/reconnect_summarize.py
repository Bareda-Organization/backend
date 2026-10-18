#!/usr/bin/env python3
"""L-2(전원 동시 재접속 · scenario7) · L-6(토큰 만료 전 재접속 · scenario8) 한 회차의 결과를 한 번에 읽는다.

k6 요약(results/<라벨>_storm.json · _reconnect.json)에서 구독·재구독·재접속 수치를, 회차 표본(results/sample_<라벨>.csv)에서 그 구간의 Hikari 대기를 뽑는다.
커넥션 대기는 서버 지표라 k6 가 못 본다 — 표본의 epoch 열로 닫은 순간(L-2) 또는 재접속 일정 구간(L-6)에 해당하는 행만 골라 최댓값을 낸다.

실행: ./reconnect_summarize.py <라벨> storm|token
종료 코드: k6 임계(thresholds)가 모두 통과이면 0, 아니면 1 (k6 종료 코드와 별개로 요약 파일에서 다시 읽는다).
"""
import csv
import json
import re
import sys
from pathlib import Path

RES = Path(__file__).resolve().parent / 'results'


def load(path):
    return json.loads(path.read_text())


def g(m, name, key='count'):
    return (m.get(name) or {}).get(key)


def fmt(v, digits=0):
    return 'n/a' if v is None else f'{v:.{digits}f}'


def sample_window(label, lo_ms, hi_ms):
    rows = list(csv.DictReader(open(RES / f'sample_{label}.csv')))
    inside = [r for r in rows if r.get('epoch') and lo_ms / 1000 <= float(r['epoch']) <= hi_ms / 1000]
    pick = lambda k: [float(r[k]) for r in inside if r.get(k) not in ('', None)]
    return {
        'rows': len(inside),
        'hikari_pending_max': max(pick('hikari_pending'), default=None),
        'hikari_acquire_max_s': max(pick('hikari_acquire_max_s'), default=None),
        'hikari_active_max': max(pick('hikari_active'), default=None),
        'tomcat_conns_max': max(pick('tomcat_conns'), default=None),
        'live_threads_max': max(pick('live_threads'), default=None),
        'heap_mb_max': max(pick('heap_mb'), default=None),
        'direct_mb_max': max(pick('direct_mb'), default=None),
        'gc_pause_max_s': max(pick('gc_pause_max_s'), default=None),
        'cpu_process_max': max(pick('cpu_process'), default=None),
    }


def thresholds_ok(doc):
    bad = []
    for name, metric in doc['metrics'].items():
        for expr, breached in (metric.get('thresholds') or {}).items():
            if breached:  # --summary-export 의 임계 값은 true = 어겼다(false = 지켰다)
                bad.append(f'{name} {expr}')
    return bad


def storm(label):
    doc = load(RES / f'{label}_storm.json')
    m = doc['metrics']
    close_at = load(RES / f'{label}_storm_meta.json')['close_at_ms']
    sub = {k: g(m, f'subscribed_{k}') for k in ('student', 'staff', 'admin')}
    total_sub = sum(v or 0 for v in sub.values())
    print(f'### {label} — L-2 전원 동시 재접속')
    print(f"  닫기 전 구독 확인 {total_sub} = 학생 채널 {sub['student']} + 학원 채널 {sub['staff']} + 관제 채널 {sub['admin']}")
    log = (RES / f'{label}_storm.log').read_text(errors='replace')
    pre = re.search(r'PRECHECK[^\n]*', log)
    print('  닫기 3초 전 확인 줄: ' + (pre.group(0) if pre else '없음'))
    print(f"  올리는 단계 접속 실패(재시도로 흡수) {fmt(g(m, 'initial_attempt_failures'))} · 닫는 순간 전에 서버가 끊은 세션 {fmt(g(m, 'closed_before_close_time'))}")
    print(f"  재구독 완료 {fmt(g(m, 'resubscribed_total'))}건 (학생 {fmt(g(m, 'resubscribed_student'))} · 학원 {fmt(g(m, 'resubscribed_staff'))} · 관제 {fmt(g(m, 'resubscribed_admin'))})")
    print(f"  전원 재구독까지(가장 늦은 세션) {fmt(g(m, 'resubscribe_ms', 'max'))}ms · p50 {fmt(g(m, 'resubscribe_ms', 'med'))} · p95 {fmt(g(m, 'resubscribe_ms', 'p(95)'))} · p99 {fmt(g(m, 'resubscribe_ms', 'p(99)'))}ms")
    print(f"  ERROR 프레임 {fmt(g(m, 'error_frames'))}건 (닫은 뒤 {fmt(g(m, 'error_frames_after_close'))}) · 재접속 시도 실패 {fmt(g(m, 'reconnect_attempt_failures'))}")
    sd = 'http_req_duration{name:snapshot}'; sf = 'http_req_failed{name:snapshot}'
    print(f"  REST 스냅샷(요청 태그 snapshot) 응답 p50 {fmt(g(m, sd, 'med'))} / p95 {fmt(g(m, sd, 'p(95)'))} / max {fmt(g(m, sd, 'max'))}ms · 실패율 {fmt(g(m, sf, 'value') if g(m, sf, 'value') is not None else g(m, sf, 'rate'), 4)}")
    w = sample_window(label, close_at - 5000, close_at + 70000)
    print(f"  닫은 뒤 70초 서버 지표({w['rows']}행): Hikari 대기(pending) 최대 {fmt(w['hikari_pending_max'])} · 연결 획득 최대 {fmt(w['hikari_acquire_max_s'], 3)}s · 활성 최대 {fmt(w['hikari_active_max'])}"
          f" · 톰캣 연결 최대 {fmt(w['tomcat_conns_max'])} · 스레드 최대 {fmt(w['live_threads_max'])} · 힙 최대 {fmt(w['heap_mb_max'])}MB · 직접 메모리 최대 {fmt(w['direct_mb_max'])}MB · GC 정지 최대 {fmt(w['gc_pause_max_s'], 3)}s")
    bad = thresholds_ok(doc)
    print('  k6 임계: ' + ('전부 통과' if not bad else '미통과 — ' + ' · '.join(bad)))
    return 1 if bad else 0


def token(label):
    doc = load(RES / f'{label}_reconnect.json')
    m = doc['metrics']
    meta = load(RES / f'{label}_reconnect_meta.json')
    start, sec = meta['start_at_ms'], meta['reconnect_sec']
    cycles = g(m, 'reconnect_cycles') or 0
    sub = {k: g(m, f'subscribed_{k}') for k in ('student', 'staff', 'admin')}
    print(f"### {label} — L-6 토큰 만료 전 재접속 (배율 {meta['rate_factor']} · 재접속 구간 {sec}초)")
    print(f"  시작 전 구독 확인 {sum(v or 0 for v in sub.values())} = 학생 채널 {sub['student']} + 학원 채널 {sub['staff']} + 관제 채널 {sub['admin']}")
    log = (RES / f'{label}_reconnect.log').read_text(errors='replace')
    pre = re.search(r'PRECHECK[^\n]*', log)
    print('  시작 전 확인 줄: ' + (pre.group(0) if pre else '없음'))
    print(f"  재접속 {cycles}회 → 초당 {cycles / sec:.2f}회 · 재발급 p50 {fmt(g(m, 'reissue_ms', 'med'))} / p95 {fmt(g(m, 'reissue_ms', 'p(95)'))} / max {fmt(g(m, 'reissue_ms', 'max'))}ms · 재발급 실패 {fmt(g(m, 'reissue_failures'))}")
    print(f"  구독 복원(닫은 뒤) p50 {fmt(g(m, 'resubscribe_ms', 'med'))} / p95 {fmt(g(m, 'resubscribe_ms', 'p(95)'))} / max {fmt(g(m, 'resubscribe_ms', 'max'))}ms · 5초 초과 {fmt(g(m, 'resubscribe_over_5s'))} · 복원 못 한 재접속 {fmt(g(m, 'reconnect_without_resubscribe'))}")
    print(f"  공백(닫은 순간 → 구독 복원 뒤 첫 메시지) p50 {fmt(g(m, 'reconnect_gap_ms', 'med'))} / p95 {fmt(g(m, 'reconnect_gap_ms', 'p(95)'))} / max {fmt(g(m, 'reconnect_gap_ms', 'max'))}ms · 표본 {fmt(g(m, 'reconnect_gap_ms'))}건")
    sd = 'http_req_duration{name:snapshot}'; sf = 'http_req_failed{name:snapshot}'
    print(f"  REST 스냅샷(요청 태그 snapshot) 응답 p50 {fmt(g(m, sd, 'med'))} / p95 {fmt(g(m, sd, 'p(95)'))} / max {fmt(g(m, sd, 'max'))}ms · 실패율 {fmt(g(m, sf, 'value') if g(m, sf, 'value') is not None else g(m, sf, 'rate'), 4)} · ERROR 프레임 {fmt(g(m, 'error_frames'))}")
    rd = 'http_req_duration{name:refresh}'; rf = 'http_req_failed{name:refresh}'
    print(f"  재발급(계획된 재접속) 요청 p50 {fmt(g(m, rd, 'med'))} / p95 {fmt(g(m, rd, 'p(95)'))} / max {fmt(g(m, rd, 'max'))}ms · 시작 때 재발급 실패(재시도로 흡수) {fmt(g(m, 'initial_reissue_failures'))}")
    w = sample_window(label, start, start + sec * 1000)
    print(f"  재접속 구간 서버 지표({w['rows']}행): Hikari 대기 최대 {fmt(w['hikari_pending_max'])} · 연결 획득 최대 {fmt(w['hikari_acquire_max_s'], 3)}s · 톰캣 연결 최대 {fmt(w['tomcat_conns_max'])} · 스레드 최대 {fmt(w['live_threads_max'])}"
          f" · 힙 최대 {fmt(w['heap_mb_max'])}MB · 직접 메모리 최대 {fmt(w['direct_mb_max'])}MB · GC 정지 최대 {fmt(w['gc_pause_max_s'], 3)}s")
    bad = thresholds_ok(doc)
    print('  k6 임계: ' + ('전부 통과' if not bad else '미통과 — ' + ' · '.join(bad)))
    return 1 if bad else 0


if __name__ == '__main__':
    label, kind = sys.argv[1], sys.argv[2]
    sys.exit(storm(label) if kind == 'storm' else token(label))
