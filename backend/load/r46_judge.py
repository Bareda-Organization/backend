#!/usr/bin/env python3
"""R46-LOAD — r3 회차를 `IMPLEMENTATION_PLAN §5.3` 의 사양 기준으로 판정한다(Ruling 484).

판정 기준은 사양이 정한 값만 쓴다(임의 수치를 만들지 않는다). 사양이 "주기·시각" 만 정한 항목은 시험이 잴 수 있는 값으로 아래처럼 환산했다.

| # | 기준 | 사양 근거 | 이 시험의 환산 |
|:-:|---|---|---|
| 1 | 위치 POST 실패 0 | §5.3 통과 기준 | `position_post_failures` == 0 |
| 2 | 위치 송신 주기 2초 유지 | NFR-03 | 송신 달성률 ≥ 95% (응답이 늦어 다음 송신이 밀리면 줄어든다) **그리고** POST p95 < 2,000ms (응답이 다음 송신을 넘기지 않음) |
| 3 | WS 배달이 5초 안 | NFR-02 | 구독한 채널마다 방송 지연 p95 ≤ 5,000ms (p99·max 는 같이 적는다) |
| 4 | 확정이 마감(출발 30분 전) 안 | C-03 · RTE-02 | 배치 도래→전량 확정 드레인 ≤ 1,800초 |
| 5 | 미확정 0 | TECH_DECISIONS §13.4 | 확정 건수 == 도래시킨 건수 · 드레인 끝의 미확정 0건 |

실행: ./r46_judge.py <라벨> [라벨 …]   (결과 파일: results/<라벨>_position.json · _poll.json · _sessions.json · _run.out)
"""
import json
import re
import sys
from pathlib import Path

RESULTS = Path(__file__).resolve().parent / 'results'
NFR02_MS = 5000
NFR03_PERIOD_MS = 2000
CONFIRM_DEADLINE_SEC = 30 * 60
MIN_SENT_RATIO = 0.95
POS_DURATION_SEC = 120  # r3_mixed.sh 의 POS_DUR


def metrics(path):
    return json.loads(path.read_text())['metrics'] if path.exists() else None


def stat(m, name, key):
    return (m or {}).get(name, {}).get(key)


def judge(label):
    pos = metrics(RESULTS / f'{label}_position.json')
    poll = metrics(RESULTS / f'{label}_poll.json')
    ses = metrics(RESULTS / f'{label}_sessions.json')
    out = (RESULTS / f'{label}_run.out').read_text()
    head = re.search(r'위치 VU=(\d+)\((\d+)초 주기', out)
    vus, interval = (int(head.group(1)), int(head.group(2))) if head else (None, None)

    rows = []  # (번호, 기준, 값, 통과 여부)
    fail = stat(pos, 'position_post_failures', 'count') or 0
    rows.append((1, '위치 POST 실패 0', f'{fail}건', fail == 0))

    sent = stat(pos, 'positions_sent_total', 'count') or 0
    target = vus * POS_DURATION_SEC / interval if vus else None
    ratio = sent / target if target else None
    p95 = stat(pos, 'position_post_duration_ms', 'p(95)')
    ok2 = ratio is not None and ratio >= MIN_SENT_RATIO and p95 is not None and p95 < NFR03_PERIOD_MS
    rows.append((2, 'NFR-03 송신 주기 유지', f'달성률 {100 * (ratio or 0):.1f}% · POST p95 {p95:.0f}ms', ok2))

    channels = []
    for name, m, key in (('학원 채널(관측자)', pos, 'ws_fanout_latency_ms'), ('관제 채널(admin 세션)', ses, 'ws_message_latency_ms'),
                         ('학부모 학생 채널', poll, 'viewer_ws_latency_ms'), ('관계자·관리자 채널', poll, 'control_ws_latency_ms')):
        v = (m or {}).get(key)
        if v:
            channels.append((name, v.get('p(95)'), v.get('p(99)'), v.get('max')))
    worst = max((c[1] for c in channels if c[1] is not None), default=None)
    detail = ' · '.join(f"{n} p95 {a:.0f}" + (f"/p99 {b:.0f}" if b is not None else '') + f"/max {c:.0f}ms" for n, a, b, c in channels)
    rows.append((3, 'NFR-02 WS 배달 ≤ 5초', detail or '측정 채널 없음', worst is not None and worst <= NFR02_MS))

    drain = re.search(r'배치 드레인: (\S+)초 \(미확정 (\d+)건\)', out)
    drain_sec = float(drain.group(1)) if drain and drain.group(1).isdigit() else None
    remain = int(drain.group(2)) if drain else None
    rows.append((4, '확정 마감(30분) 안', f'드레인 {drain_sec}초' if drain_sec is not None else '드레인 미완',
                 drain_sec is not None and drain_sec <= CONFIRM_DEADLINE_SEC))
    confirmed = re.search(r'배치: 확정 (\d+)건', out)
    n_conf = int(confirmed.group(1)) if confirmed else None
    expected = re.search(r'배치=(\d+)회차', out)
    n_exp = int(expected.group(1)) if expected else None
    rows.append((5, '미확정 0', f'확정 {n_conf}/{n_exp}건 · 남은 {remain}건', remain == 0 and n_conf == n_exp))

    # 판정 밖 참고값 — 사양에 수치가 없어 통과·실패에 넣지 않는다
    extra = []
    for name, m in (('폴링', poll),):
        for g in ('parent', 'staff'):
            req = stat(m, f'poll_{g}_requests', 'count')
            if req is not None:
                extra.append(f"폴링({g}) {req}건 실패 {stat(m, f'poll_{g}_failures', 'count') or 0}")
    conn = (stat(poll, 'ws_connect_failures', 'count') or 0) + (stat(ses, 'ws_connect_failures', 'count') or 0)
    extra.append(f'세션 연결 실패 {conn}')
    e5 = re.search(r'5xx \+(\d+)', out)
    hk = re.search(r'Hikari 연결 대기 시간초과 \+(\d+)', out)
    extra.append(f"5xx {e5.group(1) if e5 else '?'} · Hikari 시간초과 {hk.group(1) if hk else '?'}")
    return rows, extra


def main(labels):
    for label in labels:
        rows, extra = judge(label)
        allok = all(r[3] for r in rows)
        print(f"### {label} — {'통과' if allok else '미통과'}")
        for no, name, value, ok in rows:
            print(f"  {no}. [{'통과' if ok else '미통과'}] {name} — {value}")
        print('  참고(판정 밖): ' + ' · '.join(extra))


if __name__ == '__main__':
    main(sys.argv[1:])
