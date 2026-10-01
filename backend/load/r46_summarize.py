#!/usr/bin/env python3
"""R46-LOAD — 회차 출력(results/<라벨>_run.out)을 표 한 줄로 모은다.

판정 근거는 콘솔 문자열이 아니라 k6 요약 JSON 이지만, 서버 쪽 값(CPU·Hikari·방송 건수)은 r2/r3 스크립트가 run.out 에만
찍는다 — 그 줄을 정규식으로 읽는다. 호스트 간섭은 묶음 실행기가 남긴 시작·끝 시각 사이의 `r46_host_noise.log` 평균이다.

실행: ./r46_summarize.py <묶음 출력 파일> [라벨 접두사 r46_r2_ | r46_r3_]
"""
import re
import sys
from pathlib import Path

RESULTS = Path(__file__).resolve().parent / 'results'
NUM = r'(-?[\d.]+)'


def first(pattern, text, cast=float):
    m = re.search(pattern, text)
    return cast(m.group(1)) if m else None


def noise_between(start, end):
    """시작~끝(HH:MM:SS) 사이 호스트 간섭 — (측정과 무관한 프로세스 CPU% 평균, 최대 단일 프로세스 CPU%)."""
    path = RESULTS / 'r46_host_noise.log'
    if not path.exists():
        return None, None
    sums, tops = [], []
    for line in path.read_text().splitlines()[1:]:
        parts = line.split()
        if len(parts) < 4 or not (start <= parts[0] <= end):
            continue
        sums.append(float(parts[2]))
        m = re.search(r'\((\d+)\)', parts[3])
        tops.append(float(m.group(1)) if m else 0.0)
    if not sums:
        return None, None
    return sum(sums) / len(sums), max(tops)


def windows(matrix_out):
    """묶음 출력에서 라벨 → (시작 시각, 끝 시각, 호스트 판정)."""
    out, starts, flags = {}, {}, {}
    for line in Path(matrix_out).read_text().splitlines():
        m = re.match(r'### (\d\d:\d\d:\d\d) 시작 (\S+) · 호스트 (\S+)', line)
        if m:
            starts[m.group(2)], flags[m.group(2)] = m.group(1), m.group(3)
        m = re.match(r'### (\d\d:\d\d:\d\d) 끝 (\S+)', line)
        if m and m.group(2) in starts:
            out[m.group(2)] = (starts[m.group(2)], m.group(1), flags[m.group(2)])
    return out


def parse_run(label):
    path = RESULTS / f'{label}_run.out'
    if not path.exists():
        return {}
    t = path.read_text()
    row = {
        'sent': first(r'송신 (\d+)건', t), 'target': first(r'목표 (\d+)건', t),
        'fail': first(r'실패 (\d+)건', t, int), 'echo': first(r'echo (\d+)건', t),
        'rps': first(r'처리량 ' + NUM + ' req/s', t),
        'p95': first(r'position_post p95=' + NUM, t), 'avg': first(r'position_post p95=\S+ avg=' + NUM, t),
        'max': first(r'position_post p95=\S+ avg=\S+ max=' + NUM, t),
        'fan95': first(r'ws_fanout p95=' + NUM, t) or first(r'학원 채널 팬아웃 지연[^:]*: p95=' + NUM, t),
        'cpu_peak': first(r'최대 cpu=' + NUM, t), 'heap': first(r'heap=(\d+)MB', t),
        'tomcat': first(r'tomcatBusy=(\d+)', t), 'hk_act': first(r'hikariAct=(\d+)', t),
        'hk_pend': first(r'hikariPend=(\d+)', t), 'acq': first(r'acquireMax=' + NUM, t),
        'outq': first(r'outQ=(\d+)', t),
        'cores': first(r'평균 ' + NUM + ' 코어', t), 'ms_req': first(r'요청당 ' + NUM + 'ms CPU', t),
        'bcast': first(r'서버 방송 전달 \d+건 \(' + NUM + '건/s', t),
        'hk_to': first(r'Hikari 연결 대기 시간초과 \+(\d+)', t), 'e5xx': first(r'5xx \+(\d+)', t),
        'dropped': first(r'버려진 위치 방송 \+(\d+)', t),
    }
    return row


def fmt(v, spec='{:.0f}'):
    return '-' if v is None else spec.format(v)


def main(matrix_out, prefix):
    wins = windows(matrix_out)
    print('| 회차 | 호스트 | 간섭 평균/최대(%) | 송신 | 처리량 req/s | p95 ms | avg | max | 실패 | 팬아웃 p95 | JVM 코어 | 요청당 CPU ms | Hikari 대기 최대 | 획득 최대 s | 톰캣 busy | 방송 건/s | 5xx |')
    print('|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|')
    for label in sorted(wins):
        if not label.startswith(prefix):
            continue
        start, end, flag = wins[label]
        n_avg, n_top = noise_between(start, end)
        r = parse_run(label)
        print(f"| {label} | {flag} | {fmt(n_avg)}/{fmt(n_top)} | {fmt(r.get('sent'))}/{fmt(r.get('target'))} | {fmt(r.get('rps'), '{:.1f}')} "
              f"| {fmt(r.get('p95'))} | {fmt(r.get('avg'))} | {fmt(r.get('max'))} | {fmt(r.get('fail'))} | {fmt(r.get('fan95'))} "
              f"| {fmt(r.get('cores'), '{:.2f}')} | {fmt(r.get('ms_req'), '{:.1f}')} | {fmt(r.get('hk_pend'))} | {fmt(r.get('acq'), '{:.2f}')} "
              f"| {fmt(r.get('tomcat'))} | {fmt(r.get('bcast'), '{:.1f}')} | {fmt(r.get('e5xx'))} |")


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else 'r46_')
