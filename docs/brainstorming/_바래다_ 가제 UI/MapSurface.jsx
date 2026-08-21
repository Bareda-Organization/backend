const { Icon } = window.DesignSystem_9e66e1;

/* 지도 플레이스홀더 — 실제 지도 SDK 자리. 타일 이미지가 없어 CSS 면과 노선 선으로 대체합니다. */
function MapSurface({ height = 320, dark, stops = [], busAt = 0.55, changed, style }) {
  const line = dark ? 'rgba(231,239,234,.07)' : 'rgba(31,92,77,.07)';
  return (
    <div style={{
      position: 'relative', height, overflow: 'hidden',
      background: dark ? 'var(--surface-sunken)' : 'var(--green-50)',
      backgroundImage:
        'repeating-linear-gradient(0deg,' + line + ' 0 1px,transparent 1px 46px),' +
        'repeating-linear-gradient(90deg,' + line + ' 0 1px,transparent 1px 46px)',
      ...style,
    }}>
      <div style={{ position: 'absolute', left: '8%', right: '8%', top: '38%', height: 6, borderRadius: 999,
        background: changed === 'added' ? 'var(--status-boarded)' : changed === 'removed' ? 'var(--status-missed)' : 'var(--map-route)',
        opacity: .9 }} />
      <div style={{ position: 'absolute', left: '8%', width: '46%', top: '38%', height: 6, borderRadius: 999, background: 'var(--map-route)', opacity: .35 }} />
      {stops.map((s, i) => (
        <div key={i} style={{ position: 'absolute', left: (8 + (i * 84) / Math.max(stops.length - 1, 1)) + '%', top: '38%', transform: 'translate(-50%,-50%)' }}>
          <span style={{ display: 'block', width: 13, height: 13, borderRadius: 999,
            background: s.state === 'done' ? 'var(--status-boarded)' : dark ? 'var(--green-300)' : 'var(--white)',
            border: '3px solid ' + (s.state === 'done' ? 'var(--status-boarded)' : 'var(--map-route)') }} />
          <span style={{ position: 'absolute', left: '50%', top: 20, transform: 'translateX(-50%)', whiteSpace: 'nowrap',
            font: 'var(--fw-medium) 11px/1 var(--font-sans)', color: dark ? 'var(--text-secondary)' : 'var(--text-secondary)' }}>{s.name}</span>
        </div>
      ))}
      <div style={{ position: 'absolute', left: (8 + busAt * 84) + '%', top: '38%', transform: 'translate(-50%,-50%)' }}>
        <span style={{ display: 'grid', placeItems: 'center', width: 34, height: 34, borderRadius: 999,
          background: 'var(--map-bus)', color: '#3A2A06', boxShadow: '0 4px 12px rgba(18,33,28,.28)' }}>
          <Icon name="bus" size={18} />
        </span>
      </div>
      <div style={{ position: 'absolute', left: 12, bottom: 10, font: 'var(--fw-light) 11px/1.4 var(--font-sans)',
        color: dark ? 'var(--text-tertiary)' : 'var(--text-tertiary)' }}>지도 SDK 자리 — 실제 타일은 연동 시 대체</div>
    </div>
  );
}
window.MapSurface = MapSurface;
