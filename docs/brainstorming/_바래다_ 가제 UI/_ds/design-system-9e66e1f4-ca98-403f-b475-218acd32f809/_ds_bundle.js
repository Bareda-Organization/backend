/* @ds-bundle: {"format":4,"namespace":"DesignSystem_9e66e1","components":[{"name":"Badge","sourcePath":"components/core/Badge.jsx"},{"name":"Button","sourcePath":"components/core/Button.jsx"},{"name":"Card","sourcePath":"components/core/Card.jsx"},{"name":"Icon","sourcePath":"components/core/Icon.jsx"},{"name":"IconButton","sourcePath":"components/core/IconButton.jsx"},{"name":"StatusPill","sourcePath":"components/core/StatusPill.jsx"},{"name":"AlertBanner","sourcePath":"components/feedback/AlertBanner.jsx"},{"name":"BottomSheet","sourcePath":"components/feedback/BottomSheet.jsx"},{"name":"Dialog","sourcePath":"components/feedback/Dialog.jsx"},{"name":"EmptyState","sourcePath":"components/feedback/EmptyState.jsx"},{"name":"NotificationCard","sourcePath":"components/feedback/NotificationCard.jsx"},{"name":"Checkbox","sourcePath":"components/forms/Checkbox.jsx"},{"name":"CodeInput","sourcePath":"components/forms/CodeInput.jsx"},{"name":"Input","sourcePath":"components/forms/Input.jsx"},{"name":"SearchField","sourcePath":"components/forms/SearchField.jsx"},{"name":"SegmentedControl","sourcePath":"components/forms/SegmentedControl.jsx"},{"name":"Select","sourcePath":"components/forms/Select.jsx"},{"name":"Switch","sourcePath":"components/forms/Switch.jsx"},{"name":"Textarea","sourcePath":"components/forms/Textarea.jsx"},{"name":"AppHeader","sourcePath":"components/navigation/AppHeader.jsx"},{"name":"PageHeader","sourcePath":"components/navigation/PageHeader.jsx"},{"name":"SideNav","sourcePath":"components/navigation/SideNav.jsx"},{"name":"TabBar","sourcePath":"components/navigation/TabBar.jsx"},{"name":"DelayPicker","sourcePath":"components/transit/DelayPicker.jsx"},{"name":"RosterTable","sourcePath":"components/transit/RosterTable.jsx"},{"name":"RunSummaryCard","sourcePath":"components/transit/RunSummaryCard.jsx"},{"name":"StatCard","sourcePath":"components/transit/StatCard.jsx"},{"name":"StopTimeline","sourcePath":"components/transit/StopTimeline.jsx"},{"name":"StudentRow","sourcePath":"components/transit/StudentRow.jsx"}],"sourceHashes":{"components/core/Badge.jsx":"4f32d5e51c40","components/core/Button.jsx":"8238fa37be6b","components/core/Card.jsx":"3cff4637a3dd","components/core/Icon.jsx":"b75432d31fec","components/core/IconButton.jsx":"53a77460b16b","components/core/StatusPill.jsx":"e36ebc952bd9","components/feedback/AlertBanner.jsx":"44044dd836f2","components/feedback/BottomSheet.jsx":"966d861ece5a","components/feedback/Dialog.jsx":"797de7cebc8f","components/feedback/EmptyState.jsx":"bfc82c382238","components/feedback/NotificationCard.jsx":"0882a58b2cb7","components/forms/Checkbox.jsx":"a2c4b4102355","components/forms/CodeInput.jsx":"271f37eaef74","components/forms/Input.jsx":"b3708e65c576","components/forms/SearchField.jsx":"de8dc197d0d4","components/forms/SegmentedControl.jsx":"36a173e8c9e7","components/forms/Select.jsx":"c14283a34626","components/forms/Switch.jsx":"8f7e99220447","components/forms/Textarea.jsx":"8872a30b555e","components/navigation/AppHeader.jsx":"8503f63b6be6","components/navigation/PageHeader.jsx":"19ab7e15019e","components/navigation/SideNav.jsx":"68c1e63a31b4","components/navigation/TabBar.jsx":"04e2d1cb27e7","components/transit/DelayPicker.jsx":"27a45f4b8a5f","components/transit/RosterTable.jsx":"83179aceaeeb","components/transit/RunSummaryCard.jsx":"59553f87535f","components/transit/StatCard.jsx":"e35fb47ec2bf","components/transit/StopTimeline.jsx":"ce304e505431","components/transit/StudentRow.jsx":"c02eefe71033","ui_kits/MapSurface.jsx":"6782f58c6904","ui_kits/academy-web/WebScreens.jsx":"e0fa137ab776","ui_kits/academy-web/kit.jsx":"622dfbb3fc18","ui_kits/manager-app/ManagerScreens.jsx":"8bb15cbfb1a3","ui_kits/manager-app/kit.jsx":"dd2ecb82a38b","ui_kits/parent-app/ParentScreens.jsx":"24cfc00d4b32","ui_kits/parent-app/kit.jsx":"c36d52eb94db"},"inlinedExternals":[],"unexposedExports":[]} */

(() => {

const __ds_ns = (window.DesignSystem_9e66e1 = window.DesignSystem_9e66e1 || {});

const __ds_scope = {};

(__ds_ns.__errors = __ds_ns.__errors || []);

// components/core/Badge.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
const badgeTone = {
  neutral: {
    background: 'var(--stone-100)',
    color: 'var(--stone-600)'
  },
  brand: {
    background: 'var(--accent-primary-soft)',
    color: 'var(--text-brand)'
  },
  amber: {
    background: 'var(--status-moving-soft)',
    color: 'var(--status-moving)'
  },
  red: {
    background: 'var(--status-missed-soft)',
    color: 'var(--status-missed)'
  },
  added: {
    background: 'var(--status-boarded-soft)',
    color: 'var(--status-boarded)'
  },
  removed: {
    background: 'var(--status-missed-soft)',
    color: 'var(--status-missed)'
  }
};
function Badge({
  tone = 'neutral',
  count,
  children,
  style,
  ...rest
}) {
  const t = badgeTone[tone] || badgeTone.neutral;
  const isCount = count != null;
  return /*#__PURE__*/React.createElement("span", _extends({}, rest, {
    style: {
      display: 'inline-flex',
      alignItems: 'center',
      justifyContent: 'center',
      minWidth: isCount ? 20 : undefined,
      height: isCount ? 20 : undefined,
      padding: isCount ? '0 6px' : '3px 8px',
      borderRadius: 'var(--radius-pill)',
      font: 'var(--fw-bold) var(--fs-label-sm)/1 var(--font-sans)',
      ...t,
      ...style
    }
  }), isCount ? count : children);
}
Object.assign(__ds_scope, { Badge });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/Badge.jsx", error: String((e && e.message) || e) }); }

// components/core/Card.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
const cardTone = {
  base: {
    background: 'var(--surface-card)',
    boxShadow: 'var(--shadow-card)',
    border: 'none'
  },
  mist: {
    background: 'var(--bg-subtle)',
    boxShadow: 'none',
    border: 'none'
  },
  outline: {
    background: 'var(--surface-card)',
    boxShadow: 'none',
    border: '1px solid var(--border-subtle)'
  },
  inverse: {
    background: 'var(--surface-inverse)',
    boxShadow: 'var(--shadow-card)',
    border: 'none',
    color: 'var(--text-inverse)'
  }
};
function Card({
  tone = 'base',
  padding = 20,
  accent,
  onClick,
  children,
  style,
  ...rest
}) {
  const t = cardTone[tone] || cardTone.base;
  return /*#__PURE__*/React.createElement("div", _extends({
    onClick: onClick
  }, rest, {
    style: {
      borderRadius: 'var(--radius-card)',
      padding,
      cursor: onClick ? 'pointer' : undefined,
      transition: 'var(--transition-control)',
      ...t,
      ...(accent ? {
        borderTop: '3px solid var(--status-' + accent + ')'
      } : null),
      ...style
    }
  }), children);
}
Object.assign(__ds_scope, { Card });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/Card.jsx", error: String((e && e.message) || e) }); }

// components/core/Icon.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
const LUCIDE_BASE = 'https://unpkg.com/lucide-static@0.428.0/icons/';

/** 브랜드 전용 아이콘 자산을 제공받지 못해 Lucide(2px stroke, round cap)를 표준으로 씁니다.
 *  CSS mask로 그려서 색은 항상 currentColor를 따릅니다. */
function Icon({
  name,
  size = 20,
  style,
  ...rest
}) {
  const url = LUCIDE_BASE + name + '.svg';
  return /*#__PURE__*/React.createElement("span", _extends({
    "aria-hidden": "true"
  }, rest, {
    style: {
      display: 'inline-block',
      width: size,
      height: size,
      flex: 'none',
      background: 'currentColor',
      WebkitMaskImage: 'url(' + url + ')',
      maskImage: 'url(' + url + ')',
      WebkitMaskSize: 'contain',
      maskSize: 'contain',
      WebkitMaskRepeat: 'no-repeat',
      maskRepeat: 'no-repeat',
      WebkitMaskPosition: 'center',
      maskPosition: 'center',
      ...style
    }
  }));
}
Object.assign(__ds_scope, { Icon });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/Icon.jsx", error: String((e && e.message) || e) }); }

// components/core/Button.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
const btnVariant = {
  primary: {
    background: 'var(--accent-primary)',
    color: 'var(--text-inverse)',
    border: '1px solid transparent'
  },
  secondary: {
    background: 'var(--surface-card)',
    color: 'var(--text-primary)',
    border: '1px solid var(--border-default)'
  },
  soft: {
    background: 'var(--accent-primary-soft)',
    color: 'var(--text-brand)',
    border: '1px solid transparent'
  },
  ghost: {
    background: 'transparent',
    color: 'var(--text-brand)',
    border: '1px solid transparent'
  },
  danger: {
    background: 'var(--status-missed)',
    color: 'var(--white)',
    border: '1px solid transparent'
  }
};
const btnSize = {
  sm: {
    height: 36,
    padding: '0 14px',
    fontSize: 'var(--fs-label-sm)',
    gap: 6
  },
  md: {
    height: 44,
    padding: '0 18px',
    fontSize: 'var(--fs-label)',
    gap: 8
  },
  lg: {
    height: 52,
    padding: '0 22px',
    fontSize: 'var(--fs-body)',
    gap: 8
  }
};
function Button({
  variant = 'primary',
  size = 'md',
  icon,
  iconEnd,
  block,
  disabled,
  children,
  style,
  onClick,
  type = 'button',
  ...rest
}) {
  const [active, setActive] = React.useState(false);
  const [hover, setHover] = React.useState(false);
  const v = btnVariant[variant] || btnVariant.primary;
  const s = btnSize[size] || btnSize.md;
  return /*#__PURE__*/React.createElement("button", _extends({
    type: type,
    disabled: disabled,
    onClick: onClick,
    onMouseEnter: () => setHover(true),
    onMouseLeave: () => {
      setHover(false);
      setActive(false);
    },
    onMouseDown: () => setActive(true),
    onMouseUp: () => setActive(false)
  }, rest, {
    style: {
      display: block ? 'flex' : 'inline-flex',
      width: block ? '100%' : undefined,
      alignItems: 'center',
      justifyContent: 'center',
      gap: s.gap,
      height: s.height,
      padding: s.padding,
      fontSize: s.fontSize,
      fontFamily: 'var(--font-sans)',
      fontWeight: 'var(--fw-medium)',
      letterSpacing: '-0.01em',
      borderRadius: 'var(--radius-control)',
      cursor: disabled ? 'not-allowed' : 'pointer',
      transition: 'var(--transition-control), transform var(--dur-fast) var(--ease-standard)',
      transform: active && !disabled ? 'scale(var(--press-scale))' : 'none',
      opacity: disabled ? 0.42 : 1,
      filter: hover && !disabled ? 'brightness(0.94)' : 'none',
      ...v,
      ...style
    }
  }), icon ? /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: icon,
    size: size === 'sm' ? 16 : 18
  }) : null, children, iconEnd ? /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: iconEnd,
    size: size === 'sm' ? 16 : 18
  }) : null);
}
Object.assign(__ds_scope, { Button });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/Button.jsx", error: String((e && e.message) || e) }); }

// components/core/IconButton.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
const iconBtnTone = {
  plain: {
    background: 'transparent',
    color: 'var(--text-secondary)'
  },
  soft: {
    background: 'var(--accent-primary-soft)',
    color: 'var(--text-brand)'
  },
  inverse: {
    background: 'transparent',
    color: 'var(--text-on-chrome)'
  }
};
function IconButton({
  icon,
  label,
  tone = 'plain',
  size = 40,
  style,
  ...rest
}) {
  const t = iconBtnTone[tone] || iconBtnTone.plain;
  return /*#__PURE__*/React.createElement("button", _extends({
    type: "button",
    "aria-label": label,
    title: label
  }, rest, {
    style: {
      width: size,
      height: size,
      display: 'grid',
      placeItems: 'center',
      border: 'none',
      borderRadius: 'var(--radius-pill)',
      cursor: 'pointer',
      transition: 'var(--transition-control)',
      ...t,
      ...style
    }
  }), /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: icon,
    size: Math.round(size * 0.5)
  }));
}
Object.assign(__ds_scope, { IconButton });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/IconButton.jsx", error: String((e && e.message) || e) }); }

// components/core/StatusPill.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
const statusMeta = {
  boarded: {
    fg: 'var(--status-boarded)',
    bg: 'var(--status-boarded-soft)',
    icon: 'circle-check',
    label: '승차 완료'
  },
  moving: {
    fg: 'var(--status-moving)',
    bg: 'var(--status-moving-soft)',
    icon: 'bus',
    label: '이동 중'
  },
  missed: {
    fg: 'var(--status-missed)',
    bg: 'var(--status-missed-soft)',
    icon: 'circle-alert',
    label: '미탑승'
  },
  idle: {
    fg: 'var(--status-idle)',
    bg: 'var(--status-idle-soft)',
    icon: 'clock',
    label: '운행 전'
  }
};
function StatusPill({
  status = 'boarded',
  children,
  dot,
  showIcon = true,
  style,
  ...rest
}) {
  const m = statusMeta[status] || statusMeta.boarded;
  return /*#__PURE__*/React.createElement("span", _extends({}, rest, {
    style: {
      display: 'inline-flex',
      alignItems: 'center',
      gap: 6,
      padding: '5px 11px',
      borderRadius: 'var(--radius-pill)',
      background: m.bg,
      color: m.fg,
      font: 'var(--fw-medium) var(--fs-label-sm)/1.2 var(--font-sans)',
      ...style
    }
  }), dot ? /*#__PURE__*/React.createElement("span", {
    style: {
      width: 7,
      height: 7,
      borderRadius: 999,
      background: m.fg
    }
  }) : null, !dot && showIcon ? /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: m.icon,
    size: 14
  }) : null, children || m.label);
}
Object.assign(__ds_scope, { StatusPill });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/StatusPill.jsx", error: String((e && e.message) || e) }); }

// components/feedback/AlertBanner.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
const alertTone = {
  info: {
    fg: 'var(--text-brand)',
    bg: 'var(--accent-primary-soft)',
    icon: 'info'
  },
  moving: {
    fg: 'var(--status-moving)',
    bg: 'var(--status-moving-soft)',
    icon: 'bus'
  },
  missed: {
    fg: 'var(--status-missed)',
    bg: 'var(--status-missed-soft)',
    icon: 'triangle-alert'
  },
  boarded: {
    fg: 'var(--status-boarded)',
    bg: 'var(--status-boarded-soft)',
    icon: 'circle-check'
  }
};
function AlertBanner({
  tone = 'info',
  title,
  children,
  action,
  style,
  ...rest
}) {
  const t = alertTone[tone] || alertTone.info;
  return /*#__PURE__*/React.createElement("div", _extends({}, rest, {
    style: {
      display: 'flex',
      gap: 12,
      padding: '14px 16px',
      background: t.bg,
      borderRadius: 'var(--radius-md)',
      ...style
    }
  }), /*#__PURE__*/React.createElement("span", {
    style: {
      color: t.fg,
      marginTop: 2
    }
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: t.icon,
    size: 18
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1,
      minWidth: 0
    }
  }, title ? /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--fw-bold) var(--fs-body-sm)/1.4 var(--font-sans)',
      color: t.fg
    }
  }, title) : null, children ? /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: title ? 4 : 0,
      font: 'var(--fw-regular) var(--fs-body-sm)/1.6 var(--font-sans)',
      color: 'var(--text-primary)'
    }
  }, children) : null, action ? /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 10
    }
  }, action) : null));
}
Object.assign(__ds_scope, { AlertBanner });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/feedback/AlertBanner.jsx", error: String((e && e.message) || e) }); }

// components/feedback/BottomSheet.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
function BottomSheet({
  open = true,
  title,
  children,
  onClose,
  style,
  ...rest
}) {
  if (!open) return null;
  return /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'absolute',
      inset: 0,
      zIndex: 30,
      display: 'flex',
      flexDirection: 'column',
      justifyContent: 'flex-end'
    }
  }, /*#__PURE__*/React.createElement("div", {
    onClick: onClose,
    style: {
      position: 'absolute',
      inset: 0,
      background: 'var(--overlay-scrim)'
    }
  }), /*#__PURE__*/React.createElement("div", _extends({}, rest, {
    style: {
      position: 'relative',
      background: 'var(--surface-card)',
      borderRadius: 'var(--radius-sheet) var(--radius-sheet) 0 0',
      boxShadow: 'var(--shadow-sheet)',
      padding: '12px 20px 24px',
      animation: 'none',
      ...style
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      width: 40,
      height: 4,
      borderRadius: 999,
      background: 'var(--stone-200)',
      margin: '0 auto 14px'
    }
  }), title ? /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--fw-bold) 20px/1.35 var(--font-serif)',
      letterSpacing: '-0.015em',
      marginBottom: 12
    }
  }, title) : null, children));
}
Object.assign(__ds_scope, { BottomSheet });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/feedback/BottomSheet.jsx", error: String((e && e.message) || e) }); }

// components/feedback/Dialog.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
function Dialog({
  open = true,
  title,
  children,
  footer,
  onClose,
  width = 420,
  style,
  ...rest
}) {
  if (!open) return null;
  return /*#__PURE__*/React.createElement("div", {
    onClick: onClose,
    style: {
      position: 'absolute',
      inset: 0,
      background: 'var(--overlay-scrim)',
      display: 'grid',
      placeItems: 'center',
      padding: 20,
      zIndex: 40
    }
  }, /*#__PURE__*/React.createElement("div", _extends({
    onClick: e => e.stopPropagation()
  }, rest, {
    style: {
      width: '100%',
      maxWidth: width,
      background: 'var(--surface-card)',
      borderRadius: 'var(--radius-xl)',
      boxShadow: 'var(--shadow-raised)',
      padding: 24,
      ...style
    }
  }), title ? /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--fw-bold) 22px/1.35 var(--font-serif)',
      letterSpacing: '-0.015em'
    }
  }, title) : null, /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: title ? 10 : 0,
      font: 'var(--fw-regular) var(--fs-body-sm)/1.7 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, children), footer ? /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      gap: 8,
      marginTop: 22,
      justifyContent: 'flex-end'
    }
  }, footer) : null));
}
Object.assign(__ds_scope, { Dialog });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/feedback/Dialog.jsx", error: String((e && e.message) || e) }); }

// components/feedback/EmptyState.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
function EmptyState({
  icon = 'bus',
  title,
  children,
  action,
  style,
  ...rest
}) {
  return /*#__PURE__*/React.createElement("div", _extends({}, rest, {
    style: {
      padding: '48px 24px',
      textAlign: 'center',
      ...style
    }
  }), /*#__PURE__*/React.createElement("span", {
    style: {
      display: 'inline-grid',
      placeItems: 'center',
      width: 56,
      height: 56,
      borderRadius: 999,
      background: 'var(--bg-subtle)',
      color: 'var(--text-brand)'
    }
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: icon,
    size: 26
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 16,
      font: 'var(--fw-bold) 20px/1.4 var(--font-serif)',
      letterSpacing: '-0.015em'
    }
  }, title), children ? /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 8,
      font: 'var(--fw-light) var(--fs-caption)/1.7 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, children) : null, action ? /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 20,
      display: 'flex',
      justifyContent: 'center'
    }
  }, action) : null);
}
Object.assign(__ds_scope, { EmptyState });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/feedback/EmptyState.jsx", error: String((e && e.message) || e) }); }

// components/feedback/NotificationCard.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
function NotificationCard({
  status = 'boarded',
  statusLabel,
  title,
  meta,
  sub,
  time,
  unread,
  onClick,
  style,
  ...rest
}) {
  return /*#__PURE__*/React.createElement("div", _extends({
    onClick: onClick
  }, rest, {
    style: {
      display: 'flex',
      gap: 14,
      padding: '16px 18px',
      background: 'var(--surface-card)',
      borderRadius: 'var(--radius-card)',
      boxShadow: 'var(--shadow-card)',
      cursor: onClick ? 'pointer' : undefined,
      transition: 'var(--transition-control)',
      ...style
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1,
      minWidth: 0
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 8
    }
  }, /*#__PURE__*/React.createElement(__ds_scope.StatusPill, {
    status: status
  }, statusLabel), unread ? /*#__PURE__*/React.createElement("span", {
    style: {
      width: 6,
      height: 6,
      borderRadius: 999,
      background: 'var(--accent-secondary)'
    }
  }) : null, time ? /*#__PURE__*/React.createElement("span", {
    style: {
      marginLeft: 'auto',
      font: 'var(--fw-light) var(--fs-micro)/1 var(--font-sans)',
      color: 'var(--text-tertiary)'
    }
  }, time) : null), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 10,
      font: 'var(--fw-bold) 20px/1.35 var(--font-serif)',
      letterSpacing: '-0.015em'
    }
  }, title), meta ? /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 6,
      font: 'var(--fw-regular) var(--fs-body-sm)/1.6 var(--font-sans)'
    }
  }, meta) : null, sub ? /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 2,
      font: 'var(--fw-light) var(--fs-micro)/1.5 var(--font-sans)',
      letterSpacing: 'var(--ls-micro)',
      color: 'var(--text-secondary)'
    }
  }, sub) : null), onClick ? /*#__PURE__*/React.createElement("span", {
    style: {
      color: 'var(--text-tertiary)',
      alignSelf: 'center'
    }
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: "chevron-right",
    size: 20
  })) : null);
}
Object.assign(__ds_scope, { NotificationCard });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/feedback/NotificationCard.jsx", error: String((e && e.message) || e) }); }

// components/forms/Checkbox.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
function Checkbox({
  checked,
  onChange,
  label,
  sublabel,
  disabled,
  style,
  ...rest
}) {
  return /*#__PURE__*/React.createElement("label", _extends({}, rest, {
    style: {
      display: 'flex',
      alignItems: 'flex-start',
      gap: 12,
      minHeight: 44,
      cursor: disabled ? 'not-allowed' : 'pointer',
      opacity: disabled ? 0.45 : 1,
      ...style
    }
  }), /*#__PURE__*/React.createElement("input", {
    type: "checkbox",
    checked: !!checked,
    onChange: onChange,
    disabled: disabled,
    style: {
      position: 'absolute',
      opacity: 0,
      width: 0,
      height: 0
    }
  }), /*#__PURE__*/React.createElement("span", {
    style: {
      width: 22,
      height: 22,
      flex: 'none',
      marginTop: 1,
      display: 'grid',
      placeItems: 'center',
      borderRadius: 'var(--radius-xs)',
      background: checked ? 'var(--accent-primary)' : 'var(--surface-card)',
      border: '1px solid ' + (checked ? 'var(--accent-primary)' : 'var(--border-default)'),
      color: 'var(--text-inverse)',
      transition: 'var(--transition-control)'
    }
  }, checked ? /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: "check",
    size: 14
  }) : null), /*#__PURE__*/React.createElement("span", null, /*#__PURE__*/React.createElement("span", {
    style: {
      display: 'block',
      font: 'var(--fw-regular) var(--fs-body-sm)/1.5 var(--font-sans)'
    }
  }, label), sublabel ? /*#__PURE__*/React.createElement("span", {
    style: {
      display: 'block',
      font: 'var(--fw-light) var(--fs-micro)/1.5 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, sublabel) : null));
}
Object.assign(__ds_scope, { Checkbox });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/forms/Checkbox.jsx", error: String((e && e.message) || e) }); }

// components/forms/CodeInput.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
function CodeInput({
  length = 6,
  value = '',
  onChange,
  label,
  hint,
  error,
  style,
  ...rest
}) {
  const chars = Array.from({
    length
  }, (_, i) => value[i] || '');
  return /*#__PURE__*/React.createElement("div", _extends({}, rest, {
    style: style
  }), label ? /*#__PURE__*/React.createElement("span", {
    style: {
      display: 'block',
      marginBottom: 8,
      font: 'var(--fw-medium) var(--fs-label-sm)/1.2 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, label) : null, /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'relative'
    }
  }, /*#__PURE__*/React.createElement("input", {
    value: value,
    inputMode: "text",
    autoComplete: "one-time-code",
    maxLength: length,
    onChange: e => onChange && onChange(e.target.value.toUpperCase().slice(0, length)),
    style: {
      position: 'absolute',
      inset: 0,
      width: '100%',
      height: '100%',
      opacity: 0,
      cursor: 'text'
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      gap: 8
    }
  }, chars.map((c, i) => /*#__PURE__*/React.createElement("span", {
    key: i,
    style: {
      flex: 1,
      height: 56,
      display: 'grid',
      placeItems: 'center',
      background: 'var(--surface-card)',
      border: '1px solid ' + (error ? 'var(--status-missed)' : c ? 'var(--accent-primary)' : 'var(--border-default)'),
      borderRadius: 'var(--radius-control)',
      font: 'var(--fw-bold) 24px/1 var(--font-sans)',
      letterSpacing: 0,
      color: 'var(--text-primary)',
      transition: 'var(--transition-control)'
    }
  }, c || '')))), error || hint ? /*#__PURE__*/React.createElement("span", {
    style: {
      display: 'block',
      marginTop: 8,
      font: 'var(--fw-light) var(--fs-micro)/1.5 var(--font-sans)',
      color: error ? 'var(--status-missed)' : 'var(--text-secondary)'
    }
  }, error || hint) : null);
}
Object.assign(__ds_scope, { CodeInput });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/forms/CodeInput.jsx", error: String((e && e.message) || e) }); }

// components/forms/Input.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
const fieldBase = {
  width: '100%',
  height: 48,
  padding: '0 14px',
  background: 'var(--surface-card)',
  color: 'var(--text-primary)',
  border: '1px solid var(--border-default)',
  borderRadius: 'var(--radius-control)',
  font: 'var(--fw-regular) var(--fs-body)/1 var(--font-sans)',
  outline: 'none',
  transition: 'var(--transition-control)'
};
function Input({
  label,
  hint,
  error,
  icon,
  suffix,
  required,
  style,
  wrapStyle,
  ...rest
}) {
  const [focus, setFocus] = React.useState(false);
  return /*#__PURE__*/React.createElement("label", {
    style: {
      display: 'block',
      ...wrapStyle
    }
  }, label ? /*#__PURE__*/React.createElement("span", {
    style: {
      display: 'block',
      marginBottom: 6,
      font: 'var(--fw-medium) var(--fs-label-sm)/1.2 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, label, required ? /*#__PURE__*/React.createElement("span", {
    style: {
      color: 'var(--status-missed)'
    }
  }, " *") : null) : null, /*#__PURE__*/React.createElement("span", {
    style: {
      position: 'relative',
      display: 'block'
    }
  }, icon ? /*#__PURE__*/React.createElement("span", {
    style: {
      position: 'absolute',
      left: 13,
      top: '50%',
      transform: 'translateY(-50%)',
      color: 'var(--text-tertiary)',
      pointerEvents: 'none'
    }
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: icon,
    size: 18
  })) : null, /*#__PURE__*/React.createElement("input", _extends({
    onFocus: () => setFocus(true),
    onBlur: () => setFocus(false)
  }, rest, {
    style: {
      ...fieldBase,
      paddingLeft: icon ? 42 : 14,
      paddingRight: suffix ? 56 : 14,
      borderColor: error ? 'var(--status-missed)' : focus ? 'var(--focus-ring)' : 'var(--border-default)',
      boxShadow: focus ? 'var(--focus-shadow)' : 'none',
      ...style
    }
  })), suffix ? /*#__PURE__*/React.createElement("span", {
    style: {
      position: 'absolute',
      right: 14,
      top: '50%',
      transform: 'translateY(-50%)',
      font: 'var(--fw-light) var(--fs-caption)/1 var(--font-sans)',
      color: 'var(--text-tertiary)'
    }
  }, suffix) : null), error || hint ? /*#__PURE__*/React.createElement("span", {
    style: {
      display: 'block',
      marginTop: 6,
      font: 'var(--fw-light) var(--fs-micro)/1.5 var(--font-sans)',
      letterSpacing: 'var(--ls-micro)',
      color: error ? 'var(--status-missed)' : 'var(--text-secondary)'
    }
  }, error || hint) : null);
}
Object.assign(__ds_scope, { Input });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/forms/Input.jsx", error: String((e && e.message) || e) }); }

// components/forms/SearchField.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
function SearchField({
  value,
  onChange,
  onSubmit,
  placeholder = '이름으로 검색',
  style,
  ...rest
}) {
  const [focus, setFocus] = React.useState(false);
  return /*#__PURE__*/React.createElement("form", _extends({
    onSubmit: e => {
      e.preventDefault();
      onSubmit && onSubmit(value);
    }
  }, rest, {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 8,
      height: 44,
      padding: '0 6px 0 14px',
      background: 'var(--surface-card)',
      border: '1px solid ' + (focus ? 'var(--focus-ring)' : 'var(--border-default)'),
      borderRadius: 'var(--radius-pill)',
      boxShadow: focus ? 'var(--focus-shadow)' : 'none',
      transition: 'var(--transition-control)',
      ...style
    }
  }), /*#__PURE__*/React.createElement("span", {
    style: {
      color: 'var(--text-tertiary)'
    }
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: "search",
    size: 18
  })), /*#__PURE__*/React.createElement("input", {
    value: value,
    onChange: onChange,
    placeholder: placeholder,
    onFocus: () => setFocus(true),
    onBlur: () => setFocus(false),
    style: {
      flex: 1,
      minWidth: 0,
      border: 'none',
      outline: 'none',
      background: 'transparent',
      font: 'var(--fw-regular) var(--fs-body-sm)/1 var(--font-sans)'
    }
  }), /*#__PURE__*/React.createElement("button", {
    type: "submit",
    style: {
      height: 32,
      padding: '0 14px',
      border: 'none',
      borderRadius: 'var(--radius-pill)',
      background: 'var(--accent-primary)',
      color: 'var(--text-inverse)',
      cursor: 'pointer',
      font: 'var(--fw-medium) var(--fs-label-sm)/1 var(--font-sans)'
    }
  }, "\uAC80\uC0C9"));
}
Object.assign(__ds_scope, { SearchField });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/forms/SearchField.jsx", error: String((e && e.message) || e) }); }

// components/forms/SegmentedControl.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
function SegmentedControl({
  options = [],
  value,
  onChange,
  block,
  style,
  ...rest
}) {
  return /*#__PURE__*/React.createElement("div", _extends({
    role: "tablist"
  }, rest, {
    style: {
      display: block ? 'flex' : 'inline-flex',
      width: block ? '100%' : undefined,
      gap: 4,
      padding: 4,
      background: 'var(--bg-subtle)',
      borderRadius: 'var(--radius-control)',
      ...style
    }
  }), options.map(o => {
    const opt = typeof o === 'string' ? {
      value: o,
      label: o
    } : o;
    const on = opt.value === value;
    return /*#__PURE__*/React.createElement("button", {
      key: opt.value,
      role: "tab",
      "aria-selected": on,
      type: "button",
      onClick: () => onChange && onChange(opt.value),
      style: {
        flex: block ? 1 : undefined,
        height: 38,
        padding: '0 16px',
        border: 'none',
        borderRadius: 'var(--radius-sm)',
        cursor: 'pointer',
        background: on ? 'var(--surface-card)' : 'transparent',
        boxShadow: on ? 'var(--shadow-sm)' : 'none',
        color: on ? 'var(--text-primary)' : 'var(--text-secondary)',
        font: (on ? 'var(--fw-medium)' : 'var(--fw-regular)') + ' var(--fs-label-sm)/1 var(--font-sans)',
        transition: 'var(--transition-control)'
      }
    }, opt.label);
  }));
}
Object.assign(__ds_scope, { SegmentedControl });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/forms/SegmentedControl.jsx", error: String((e && e.message) || e) }); }

// components/forms/Select.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
const fieldBase = {
  width: '100%',
  height: 48,
  padding: '0 14px',
  background: 'var(--surface-card)',
  color: 'var(--text-primary)',
  border: '1px solid var(--border-default)',
  borderRadius: 'var(--radius-control)',
  font: 'var(--fw-regular) var(--fs-body)/1 var(--font-sans)',
  outline: 'none',
  transition: 'var(--transition-control)'
};
function Select({
  label,
  hint,
  options = [],
  value,
  onChange,
  style,
  wrapStyle,
  ...rest
}) {
  const [focus, setFocus] = React.useState(false);
  return /*#__PURE__*/React.createElement("label", {
    style: {
      display: 'block',
      ...wrapStyle
    }
  }, label ? /*#__PURE__*/React.createElement("span", {
    style: {
      display: 'block',
      marginBottom: 6,
      font: 'var(--fw-medium) var(--fs-label-sm)/1.2 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, label) : null, /*#__PURE__*/React.createElement("span", {
    style: {
      position: 'relative',
      display: 'block'
    }
  }, /*#__PURE__*/React.createElement("select", _extends({
    value: value,
    onChange: onChange,
    onFocus: () => setFocus(true),
    onBlur: () => setFocus(false)
  }, rest, {
    style: {
      ...fieldBase,
      appearance: 'none',
      paddingRight: 40,
      cursor: 'pointer',
      borderColor: focus ? 'var(--focus-ring)' : 'var(--border-default)',
      boxShadow: focus ? 'var(--focus-shadow)' : 'none',
      ...style
    }
  }), options.map(o => {
    const opt = typeof o === 'string' ? {
      value: o,
      label: o
    } : o;
    return /*#__PURE__*/React.createElement("option", {
      key: opt.value,
      value: opt.value
    }, opt.label);
  })), /*#__PURE__*/React.createElement("span", {
    style: {
      position: 'absolute',
      right: 13,
      top: '50%',
      transform: 'translateY(-50%)',
      color: 'var(--text-tertiary)',
      pointerEvents: 'none'
    }
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: "chevron-down",
    size: 18
  }))), hint ? /*#__PURE__*/React.createElement("span", {
    style: {
      display: 'block',
      marginTop: 6,
      font: 'var(--fw-light) var(--fs-micro)/1.5 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, hint) : null);
}
Object.assign(__ds_scope, { Select });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/forms/Select.jsx", error: String((e && e.message) || e) }); }

// components/forms/Switch.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
function Switch({
  checked,
  onChange,
  label,
  sublabel,
  disabled,
  style,
  ...rest
}) {
  return /*#__PURE__*/React.createElement("label", _extends({}, rest, {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 14,
      minHeight: 48,
      cursor: disabled ? 'not-allowed' : 'pointer',
      opacity: disabled ? 0.45 : 1,
      ...style
    }
  }), /*#__PURE__*/React.createElement("span", {
    style: {
      flex: 1
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      display: 'block',
      font: 'var(--fw-regular) var(--fs-body)/1.5 var(--font-sans)'
    }
  }, label), sublabel ? /*#__PURE__*/React.createElement("span", {
    style: {
      display: 'block',
      font: 'var(--fw-light) var(--fs-micro)/1.5 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, sublabel) : null), /*#__PURE__*/React.createElement("input", {
    type: "checkbox",
    checked: !!checked,
    onChange: onChange,
    disabled: disabled,
    style: {
      position: 'absolute',
      opacity: 0,
      width: 0,
      height: 0
    }
  }), /*#__PURE__*/React.createElement("span", {
    style: {
      width: 46,
      height: 28,
      flex: 'none',
      borderRadius: 999,
      padding: 3,
      background: checked ? 'var(--accent-primary)' : 'var(--stone-300)',
      transition: 'background-color var(--dur-fast) var(--ease-standard)'
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      display: 'block',
      width: 22,
      height: 22,
      borderRadius: 999,
      background: 'var(--white)',
      boxShadow: 'var(--shadow-sm)',
      transform: checked ? 'translateX(18px)' : 'translateX(0)',
      transition: 'transform var(--dur-fast) var(--ease-standard)'
    }
  })));
}
Object.assign(__ds_scope, { Switch });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/forms/Switch.jsx", error: String((e && e.message) || e) }); }

// components/forms/Textarea.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
function Textarea({
  label,
  hint,
  rows = 4,
  style,
  wrapStyle,
  ...rest
}) {
  const [focus, setFocus] = React.useState(false);
  return /*#__PURE__*/React.createElement("label", {
    style: {
      display: 'block',
      ...wrapStyle
    }
  }, label ? /*#__PURE__*/React.createElement("span", {
    style: {
      display: 'block',
      marginBottom: 6,
      font: 'var(--fw-medium) var(--fs-label-sm)/1.2 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, label) : null, /*#__PURE__*/React.createElement("textarea", _extends({
    rows: rows,
    onFocus: () => setFocus(true),
    onBlur: () => setFocus(false)
  }, rest, {
    style: {
      width: '100%',
      padding: '12px 14px',
      resize: 'vertical',
      background: 'var(--surface-card)',
      color: 'var(--text-primary)',
      border: '1px solid ' + (focus ? 'var(--focus-ring)' : 'var(--border-default)'),
      borderRadius: 'var(--radius-control)',
      outline: 'none',
      boxShadow: focus ? 'var(--focus-shadow)' : 'none',
      font: 'var(--fw-regular) var(--fs-body)/var(--lh-body) var(--font-sans)',
      transition: 'var(--transition-control)',
      ...style
    }
  })), hint ? /*#__PURE__*/React.createElement("span", {
    style: {
      display: 'block',
      marginTop: 6,
      font: 'var(--fw-light) var(--fs-micro)/1.5 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, hint) : null);
}
Object.assign(__ds_scope, { Textarea });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/forms/Textarea.jsx", error: String((e && e.message) || e) }); }

// components/navigation/AppHeader.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
function AppHeader({
  title,
  subtitle,
  back,
  onBack,
  actions,
  tone = 'brand',
  style,
  ...rest
}) {
  const inverse = tone === 'brand';
  return /*#__PURE__*/React.createElement("header", _extends({}, rest, {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 10,
      minHeight: 'var(--header-h)',
      padding: '0 12px 0 6px',
      background: inverse ? 'var(--surface-chrome)' : 'var(--bg-base)',
      color: inverse ? 'var(--text-on-chrome)' : 'var(--text-primary)',
      borderBottom: '1px solid ' + (inverse ? 'var(--border-chrome)' : 'var(--border-subtle)'),
      ...style
    }
  }), back ? /*#__PURE__*/React.createElement("button", {
    type: "button",
    onClick: onBack,
    "aria-label": "\uB4A4\uB85C",
    style: {
      width: 44,
      height: 44,
      display: 'grid',
      placeItems: 'center',
      background: 'transparent',
      border: 'none',
      color: 'inherit',
      cursor: 'pointer'
    }
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: "chevron-left",
    size: 22
  })) : /*#__PURE__*/React.createElement("span", {
    style: {
      width: 12
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1,
      minWidth: 0
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--fw-bold) 17px/1.3 var(--font-sans)',
      letterSpacing: '-0.01em',
      whiteSpace: 'nowrap',
      overflow: 'hidden',
      textOverflow: 'ellipsis'
    }
  }, title), subtitle ? /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--fw-light) var(--fs-micro)/1.4 var(--font-sans)',
      letterSpacing: 'var(--ls-micro)',
      color: inverse ? 'var(--text-on-chrome-muted)' : 'var(--text-secondary)'
    }
  }, subtitle) : null), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 2
    }
  }, actions));
}
Object.assign(__ds_scope, { AppHeader });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/navigation/AppHeader.jsx", error: String((e && e.message) || e) }); }

// components/navigation/PageHeader.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
function PageHeader({
  title,
  description,
  actions,
  tabs,
  style,
  ...rest
}) {
  return /*#__PURE__*/React.createElement("div", _extends({}, rest, {
    style: {
      padding: '26px var(--gutter-desktop) 0',
      ...style
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'flex-end',
      gap: 20,
      flexWrap: 'wrap'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1,
      minWidth: 240
    }
  }, /*#__PURE__*/React.createElement("h2", {
    style: {
      font: 'var(--fw-bold) 30px/1.25 var(--font-serif)',
      letterSpacing: '-0.02em'
    }
  }, title), description ? /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 6,
      font: 'var(--fw-light) var(--fs-caption)/1.6 var(--font-sans)',
      letterSpacing: 'var(--ls-caption)',
      color: 'var(--text-secondary)'
    }
  }, description) : null), actions ? /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      gap: 8
    }
  }, actions) : null), tabs ? /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 20,
      borderBottom: '1px solid var(--border-subtle)'
    }
  }, tabs) : null);
}
Object.assign(__ds_scope, { PageHeader });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/navigation/PageHeader.jsx", error: String((e && e.message) || e) }); }

// components/navigation/SideNav.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
function SideNav({
  items = [],
  value,
  onChange,
  academy,
  style,
  ...rest
}) {
  return /*#__PURE__*/React.createElement("aside", _extends({}, rest, {
    style: {
      width: 'var(--sidenav-w)',
      flex: 'none',
      display: 'flex',
      flexDirection: 'column',
      background: 'var(--surface-chrome)',
      color: 'var(--text-on-chrome)',
      borderRight: '1px solid var(--border-chrome)',
      padding: '20px 12px',
      ...style
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '0 10px 20px'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--fw-black) 24px/1 var(--font-serif)',
      letterSpacing: '-0.03em'
    }
  }, "\uBC14\uB798\uB2E4"), academy ? /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 6,
      font: 'var(--fw-light) var(--fs-micro)/1.4 var(--font-sans)',
      color: 'var(--text-on-chrome-muted)'
    }
  }, academy) : null), /*#__PURE__*/React.createElement("nav", {
    style: {
      display: 'flex',
      flexDirection: 'column',
      gap: 2
    }
  }, items.map(it => {
    const on = it.value === value;
    return /*#__PURE__*/React.createElement("button", {
      key: it.value,
      type: "button",
      onClick: () => onChange && onChange(it.value),
      style: {
        display: 'flex',
        alignItems: 'center',
        gap: 10,
        height: 44,
        padding: '0 10px',
        background: on ? 'var(--nav-active-bg)' : 'transparent',
        border: 'none',
        borderRadius: 'var(--radius-sm)',
        cursor: 'pointer',
        color: on ? 'var(--nav-active-text)' : 'var(--nav-text)',
        font: (on ? 'var(--fw-medium)' : 'var(--fw-regular)') + ' var(--fs-body-sm)/1 var(--font-sans)',
        textAlign: 'left',
        transition: 'var(--transition-control)'
      }
    }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
      name: it.icon,
      size: 18
    }), /*#__PURE__*/React.createElement("span", {
      style: {
        flex: 1
      }
    }, it.label), it.badge ? /*#__PURE__*/React.createElement("span", {
      style: {
        minWidth: 20,
        height: 20,
        padding: '0 6px',
        borderRadius: 999,
        background: 'var(--status-missed)',
        color: 'var(--white)',
        font: 'var(--fw-bold) 11px/20px var(--font-sans)',
        textAlign: 'center'
      }
    }, it.badge) : null);
  })));
}
Object.assign(__ds_scope, { SideNav });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/navigation/SideNav.jsx", error: String((e && e.message) || e) }); }

// components/navigation/TabBar.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
function TabBar({
  items = [],
  value,
  onChange,
  style,
  ...rest
}) {
  return /*#__PURE__*/React.createElement("nav", _extends({}, rest, {
    style: {
      display: 'flex',
      height: 'var(--tabbar-h)',
      background: 'var(--surface-card)',
      borderTop: '1px solid var(--border-subtle)',
      ...style
    }
  }), items.map(it => {
    const on = it.value === value;
    return /*#__PURE__*/React.createElement("button", {
      key: it.value,
      type: "button",
      onClick: () => onChange && onChange(it.value),
      style: {
        flex: 1,
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 4,
        background: 'transparent',
        border: 'none',
        cursor: 'pointer',
        color: on ? 'var(--accent-primary)' : 'var(--text-tertiary)',
        transition: 'var(--transition-control)',
        position: 'relative'
      }
    }, /*#__PURE__*/React.createElement("span", {
      style: {
        position: 'relative'
      }
    }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
      name: it.icon,
      size: 24
    }), it.badge ? /*#__PURE__*/React.createElement("span", {
      style: {
        position: 'absolute',
        top: -3,
        right: -8,
        minWidth: 16,
        height: 16,
        padding: '0 4px',
        borderRadius: 999,
        background: 'var(--status-missed)',
        color: 'var(--white)',
        font: 'var(--fw-bold) 10px/16px var(--font-sans)',
        textAlign: 'center'
      }
    }, it.badge) : null), /*#__PURE__*/React.createElement("span", {
      style: {
        font: (on ? 'var(--fw-medium)' : 'var(--fw-regular)') + ' 11px/1 var(--font-sans)'
      }
    }, it.label));
  }));
}
Object.assign(__ds_scope, { TabBar });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/navigation/TabBar.jsx", error: String((e && e.message) || e) }); }

// components/transit/DelayPicker.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
const DELAY_OPTIONS = [5, 10, 15, 20, 25, 30];
function DelayPicker({
  value,
  onChange,
  options = DELAY_OPTIONS,
  style,
  ...rest
}) {
  return /*#__PURE__*/React.createElement("div", _extends({}, rest, {
    style: style
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--fw-medium) var(--fs-label-sm)/1.2 var(--font-sans)',
      color: 'var(--text-secondary)',
      marginBottom: 10
    }
  }, "\uC9C0\uC5F0 \uC2DC\uAC04 \xB7 5\uBD84 \uB2E8\uC704"), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'grid',
      gridTemplateColumns: 'repeat(3, 1fr)',
      gap: 8
    }
  }, options.map(m => {
    const on = m === value;
    return /*#__PURE__*/React.createElement("button", {
      key: m,
      type: "button",
      onClick: () => onChange && onChange(m),
      style: {
        height: 52,
        borderRadius: 'var(--radius-control)',
        cursor: 'pointer',
        background: on ? 'var(--status-moving-soft)' : 'var(--surface-card)',
        border: '1px solid ' + (on ? 'var(--status-moving)' : 'var(--border-default)'),
        color: on ? 'var(--status-moving)' : 'var(--text-primary)',
        font: 'var(--fw-bold) var(--fs-body)/1 var(--font-sans)',
        transition: 'var(--transition-control)'
      }
    }, m, "\uBD84");
  })));
}
Object.assign(__ds_scope, { DelayPicker });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/transit/DelayPicker.jsx", error: String((e && e.message) || e) }); }

// components/transit/RosterTable.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
function RosterTable({
  columns = [],
  rows = [],
  onRowClick,
  style,
  ...rest
}) {
  return /*#__PURE__*/React.createElement("div", _extends({}, rest, {
    style: {
      background: 'var(--surface-card)',
      borderRadius: 'var(--radius-card)',
      boxShadow: 'var(--shadow-card)',
      overflow: 'hidden',
      ...style
    }
  }), /*#__PURE__*/React.createElement("table", {
    style: {
      width: '100%',
      borderCollapse: 'collapse',
      font: 'var(--fw-regular) var(--fs-body-sm)/1.5 var(--font-sans)'
    }
  }, /*#__PURE__*/React.createElement("thead", null, /*#__PURE__*/React.createElement("tr", {
    style: {
      background: 'var(--bg-subtle)'
    }
  }, columns.map(c => /*#__PURE__*/React.createElement("th", {
    key: c.key,
    style: {
      textAlign: c.align || 'left',
      padding: '12px 16px',
      font: 'var(--fw-medium) var(--fs-micro)/1 var(--font-sans)',
      letterSpacing: 'var(--ls-micro)',
      color: 'var(--text-secondary)',
      whiteSpace: 'nowrap',
      width: c.width
    }
  }, c.label)))), /*#__PURE__*/React.createElement("tbody", null, rows.map((r, i) => /*#__PURE__*/React.createElement("tr", {
    key: r.id || i,
    onClick: () => onRowClick && onRowClick(r),
    style: {
      borderTop: '1px solid var(--border-subtle)',
      cursor: onRowClick ? 'pointer' : undefined
    }
  }, columns.map(c => /*#__PURE__*/React.createElement("td", {
    key: c.key,
    style: {
      textAlign: c.align || 'left',
      padding: '13px 16px',
      verticalAlign: 'middle'
    }
  }, c.render ? c.render(r) : r[c.key])))))));
}
Object.assign(__ds_scope, { RosterTable });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/transit/RosterTable.jsx", error: String((e && e.message) || e) }); }

// components/transit/RunSummaryCard.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
function RunSummaryCard({
  bus,
  leg,
  status = 'moving',
  statusLabel,
  eta,
  currentStop,
  nextStop,
  manager,
  driver,
  onClick,
  style,
  ...rest
}) {
  return /*#__PURE__*/React.createElement("div", _extends({
    onClick: onClick
  }, rest, {
    style: {
      background: 'var(--surface-card)',
      borderRadius: 'var(--radius-card)',
      boxShadow: 'var(--shadow-card)',
      padding: 20,
      cursor: onClick ? 'pointer' : undefined,
      ...style
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 8
    }
  }, /*#__PURE__*/React.createElement(__ds_scope.StatusPill, {
    status: status
  }, statusLabel), /*#__PURE__*/React.createElement("span", {
    style: {
      marginLeft: 'auto',
      font: 'var(--fw-bold) var(--fs-label-sm)/1 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, bus, leg ? ' · ' + leg : '')), eta ? /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 12,
      font: 'var(--fw-bold) 26px/1.3 var(--font-serif)',
      letterSpacing: '-0.015em'
    }
  }, eta) : null, /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 14,
      display: 'grid',
      gridTemplateColumns: '1fr 1fr',
      gap: 12
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      background: 'var(--bg-subtle)',
      borderRadius: 'var(--radius-md)',
      padding: '12px 14px'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 5,
      font: 'var(--fw-medium) var(--fs-micro)/1 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: "navigation",
    size: 13
  }), "\uD604\uC7AC \uC774\uB3D9 \uC911"), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 6,
      font: 'var(--fw-medium) var(--fs-body-sm)/1.3 var(--font-sans)'
    }
  }, currentStop)), /*#__PURE__*/React.createElement("div", {
    style: {
      background: 'var(--bg-subtle)',
      borderRadius: 'var(--radius-md)',
      padding: '12px 14px'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 5,
      font: 'var(--fw-medium) var(--fs-micro)/1 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: "map-pin",
    size: 13
  }), "\uB2E4\uC74C \uC815\uB958\uC7A5"), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 6,
      font: 'var(--fw-medium) var(--fs-body-sm)/1.3 var(--font-sans)'
    }
  }, nextStop))), driver || manager ? /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 14,
      font: 'var(--fw-light) var(--fs-micro)/1.5 var(--font-sans)',
      letterSpacing: 'var(--ls-micro)',
      color: 'var(--text-secondary)'
    }
  }, [driver ? '기사 ' + driver : null, manager ? '동승 매니저 ' + manager : null].filter(Boolean).join(' · ')) : null);
}
Object.assign(__ds_scope, { RunSummaryCard });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/transit/RunSummaryCard.jsx", error: String((e && e.message) || e) }); }

// components/transit/StatCard.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
const statTone = {
  neutral: 'var(--text-primary)',
  boarded: 'var(--status-boarded)',
  moving: 'var(--status-moving)',
  missed: 'var(--status-missed)'
};
function StatCard({
  label,
  value,
  unit,
  tone = 'neutral',
  icon,
  sub,
  style,
  ...rest
}) {
  return /*#__PURE__*/React.createElement("div", _extends({}, rest, {
    style: {
      background: 'var(--surface-card)',
      borderRadius: 'var(--radius-card)',
      boxShadow: 'var(--shadow-card)',
      padding: 18,
      ...style
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 6,
      font: 'var(--fw-medium) var(--fs-micro)/1 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, icon ? /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: icon,
    size: 14
  }) : null, label), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 10,
      display: 'flex',
      alignItems: 'baseline',
      gap: 4
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      font: 'var(--fw-bold) 30px/1 var(--font-sans)',
      fontVariantNumeric: 'tabular-nums',
      color: statTone[tone] || statTone.neutral
    }
  }, value), unit ? /*#__PURE__*/React.createElement("span", {
    style: {
      font: 'var(--fw-medium) var(--fs-body-sm)/1 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, unit) : null), sub ? /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 6,
      font: 'var(--fw-light) var(--fs-micro)/1.4 var(--font-sans)',
      color: 'var(--text-tertiary)'
    }
  }, sub) : null);
}
Object.assign(__ds_scope, { StatCard });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/transit/StatCard.jsx", error: String((e && e.message) || e) }); }

// components/transit/StopTimeline.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
const stopState = {
  done: {
    dot: 'var(--status-boarded)',
    label: 'var(--text-secondary)'
  },
  current: {
    dot: 'var(--status-moving)',
    label: 'var(--text-primary)'
  },
  next: {
    dot: 'var(--stone-300)',
    label: 'var(--text-primary)'
  },
  upcoming: {
    dot: 'var(--stone-300)',
    label: 'var(--text-secondary)'
  }
};
function StopTimeline({
  stops = [],
  onSelect,
  dense,
  style,
  ...rest
}) {
  return /*#__PURE__*/React.createElement("ol", _extends({}, rest, {
    style: {
      listStyle: 'none',
      margin: 0,
      padding: 0,
      ...style
    }
  }), stops.map((s, i) => {
    const st = stopState[s.state] || stopState.upcoming;
    const last = i === stops.length - 1;
    const isCurrent = s.state === 'current';
    return /*#__PURE__*/React.createElement("li", {
      key: s.name + i,
      style: {
        display: 'flex',
        gap: 14,
        cursor: onSelect ? 'pointer' : undefined
      },
      onClick: () => onSelect && onSelect(s, i)
    }, /*#__PURE__*/React.createElement("div", {
      style: {
        width: 24,
        flex: 'none',
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center'
      }
    }, /*#__PURE__*/React.createElement("span", {
      style: {
        width: isCurrent ? 24 : 12,
        height: isCurrent ? 24 : 12,
        marginTop: 4,
        borderRadius: 999,
        background: isCurrent ? 'var(--status-moving)' : st.dot,
        display: 'grid',
        placeItems: 'center',
        color: 'var(--white)',
        flex: 'none',
        boxShadow: isCurrent ? '0 0 0 4px var(--status-moving-soft)' : 'none'
      }
    }, isCurrent ? /*#__PURE__*/React.createElement(__ds_scope.Icon, {
      name: "bus",
      size: 13
    }) : null), !last ? /*#__PURE__*/React.createElement("span", {
      style: {
        flex: 1,
        width: 2,
        background: s.state === 'done' ? 'var(--status-boarded)' : 'var(--stone-200)',
        minHeight: dense ? 18 : 26
      }
    }) : null), /*#__PURE__*/React.createElement("div", {
      style: {
        flex: 1,
        minWidth: 0,
        paddingBottom: last ? 0 : dense ? 12 : 18
      }
    }, /*#__PURE__*/React.createElement("div", {
      style: {
        display: 'flex',
        alignItems: 'baseline',
        gap: 8
      }
    }, /*#__PURE__*/React.createElement("span", {
      style: {
        font: (isCurrent ? 'var(--fw-bold)' : 'var(--fw-medium)') + ' var(--fs-body-sm)/1.4 var(--font-sans)',
        color: st.label
      }
    }, s.name), s.time ? /*#__PURE__*/React.createElement("span", {
      style: {
        marginLeft: 'auto',
        font: 'var(--fw-bold) var(--fs-micro)/1 var(--font-sans)',
        fontVariantNumeric: 'tabular-nums',
        color: isCurrent ? 'var(--status-moving)' : 'var(--text-tertiary)'
      }
    }, s.time) : null), s.address ? /*#__PURE__*/React.createElement("div", {
      style: {
        marginTop: 2,
        font: 'var(--fw-light) var(--fs-micro)/1.5 var(--font-sans)',
        letterSpacing: 'var(--ls-micro)',
        color: 'var(--text-tertiary)'
      }
    }, s.address) : null, s.riders != null ? /*#__PURE__*/React.createElement("div", {
      style: {
        marginTop: 6,
        display: 'flex',
        alignItems: 'center',
        gap: 6,
        font: 'var(--fw-regular) var(--fs-micro)/1 var(--font-sans)',
        color: 'var(--text-secondary)'
      }
    }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
      name: "users-round",
      size: 13
    }), s.riders, "\uBA85", s.missed ? /*#__PURE__*/React.createElement("span", {
      style: {
        color: 'var(--status-missed)',
        fontWeight: 'var(--fw-bold)'
      }
    }, "\xB7 \uBBF8\uD0D1\uC2B9 ", s.missed) : null) : null));
  }));
}
Object.assign(__ds_scope, { StopTimeline });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/transit/StopTimeline.jsx", error: String((e && e.message) || e) }); }

// components/transit/StudentRow.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
const rideMeta = {
  boarded: {
    label: '탑승 완료',
    status: 'boarded'
  },
  alighted: {
    label: '하차 완료',
    status: 'boarded'
  },
  absent: {
    label: '미등원',
    status: 'idle'
  },
  missed: {
    label: '미탑승',
    status: 'missed'
  },
  waiting: {
    label: '대기',
    status: 'idle'
  }
};
function StudentRow({
  name,
  meta,
  phone,
  ride = 'waiting',
  selected,
  onSelect,
  onCall,
  actions,
  style,
  ...rest
}) {
  const m = rideMeta[ride] || rideMeta.waiting;
  return /*#__PURE__*/React.createElement("div", _extends({}, rest, {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 12,
      minHeight: 64,
      padding: '10px 16px',
      background: selected ? 'var(--bg-subtle)' : 'var(--surface-card)',
      borderBottom: '1px solid var(--border-subtle)',
      cursor: onSelect ? 'pointer' : undefined,
      transition: 'var(--transition-control)',
      ...style
    },
    onClick: onSelect
  }), /*#__PURE__*/React.createElement("span", {
    style: {
      width: 38,
      height: 38,
      flex: 'none',
      borderRadius: 999,
      background: 'var(--bg-subtle)',
      color: 'var(--text-brand)',
      display: 'grid',
      placeItems: 'center',
      font: 'var(--fw-bold) 14px/1 var(--font-sans)'
    }
  }, name ? name.slice(-2) : ''), /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1,
      minWidth: 0
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--fw-medium) var(--fs-body-sm)/1.4 var(--font-sans)'
    }
  }, name), meta ? /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--fw-light) var(--fs-micro)/1.5 var(--font-sans)',
      letterSpacing: 'var(--ls-micro)',
      color: 'var(--text-secondary)'
    }
  }, meta) : null), phone && onCall ? /*#__PURE__*/React.createElement("button", {
    type: "button",
    onClick: e => {
      e.stopPropagation();
      onCall();
    },
    "aria-label": "\uBCF4\uD638\uC790\uC5D0\uAC8C \uC5F0\uB77D",
    style: {
      width: 38,
      height: 38,
      borderRadius: 999,
      border: '1px solid var(--border-subtle)',
      background: 'var(--surface-card)',
      color: 'var(--text-secondary)',
      display: 'grid',
      placeItems: 'center',
      cursor: 'pointer'
    }
  }, /*#__PURE__*/React.createElement(__ds_scope.Icon, {
    name: "phone",
    size: 16
  })) : null, actions || /*#__PURE__*/React.createElement(__ds_scope.StatusPill, {
    status: m.status,
    showIcon: false
  }, m.label));
}
Object.assign(__ds_scope, { StudentRow });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/transit/StudentRow.jsx", error: String((e && e.message) || e) }); }

// ui_kits/MapSurface.jsx
try { (() => {
const {
  Icon
} = window.DesignSystem_9e66e1;

/* 지도 플레이스홀더 — 실제 지도 SDK 자리. 타일 이미지가 없어 CSS 면과 노선 선으로 대체합니다. */
function MapSurface({
  height = 320,
  dark,
  stops = [],
  busAt = 0.55,
  changed,
  style
}) {
  const line = dark ? 'rgba(231,239,234,.07)' : 'rgba(31,92,77,.07)';
  return /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'relative',
      height,
      overflow: 'hidden',
      background: dark ? 'var(--surface-sunken)' : 'var(--green-50)',
      backgroundImage: 'repeating-linear-gradient(0deg,' + line + ' 0 1px,transparent 1px 46px),' + 'repeating-linear-gradient(90deg,' + line + ' 0 1px,transparent 1px 46px)',
      ...style
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'absolute',
      left: '8%',
      right: '8%',
      top: '38%',
      height: 6,
      borderRadius: 999,
      background: changed === 'added' ? 'var(--status-boarded)' : changed === 'removed' ? 'var(--status-missed)' : 'var(--map-route)',
      opacity: .9
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'absolute',
      left: '8%',
      width: '46%',
      top: '38%',
      height: 6,
      borderRadius: 999,
      background: 'var(--map-route)',
      opacity: .35
    }
  }), stops.map((s, i) => /*#__PURE__*/React.createElement("div", {
    key: i,
    style: {
      position: 'absolute',
      left: 8 + i * 84 / Math.max(stops.length - 1, 1) + '%',
      top: '38%',
      transform: 'translate(-50%,-50%)'
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      display: 'block',
      width: 13,
      height: 13,
      borderRadius: 999,
      background: s.state === 'done' ? 'var(--status-boarded)' : dark ? 'var(--green-300)' : 'var(--white)',
      border: '3px solid ' + (s.state === 'done' ? 'var(--status-boarded)' : 'var(--map-route)')
    }
  }), /*#__PURE__*/React.createElement("span", {
    style: {
      position: 'absolute',
      left: '50%',
      top: 20,
      transform: 'translateX(-50%)',
      whiteSpace: 'nowrap',
      font: 'var(--fw-medium) 11px/1 var(--font-sans)',
      color: dark ? 'var(--text-secondary)' : 'var(--text-secondary)'
    }
  }, s.name))), /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'absolute',
      left: 8 + busAt * 84 + '%',
      top: '38%',
      transform: 'translate(-50%,-50%)'
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      display: 'grid',
      placeItems: 'center',
      width: 34,
      height: 34,
      borderRadius: 999,
      background: 'var(--map-bus)',
      color: '#3A2A06',
      boxShadow: '0 4px 12px rgba(18,33,28,.28)'
    }
  }, /*#__PURE__*/React.createElement(Icon, {
    name: "bus",
    size: 18
  }))), /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'absolute',
      left: 12,
      bottom: 10,
      font: 'var(--fw-light) 11px/1.4 var(--font-sans)',
      color: dark ? 'var(--text-tertiary)' : 'var(--text-tertiary)'
    }
  }, "\uC9C0\uB3C4 SDK \uC790\uB9AC \u2014 \uC2E4\uC81C \uD0C0\uC77C\uC740 \uC5F0\uB3D9 \uC2DC \uB300\uCCB4"));
}
window.MapSurface = MapSurface;
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/MapSurface.jsx", error: String((e && e.message) || e) }); }

// ui_kits/academy-web/WebScreens.jsx
try { (() => {
const {
  Button,
  IconButton,
  Card,
  StatusPill,
  Badge,
  Icon,
  Input,
  Textarea,
  Select,
  Checkbox,
  SegmentedControl,
  SearchField,
  AlertBanner,
  Dialog,
  EmptyState,
  SideNav,
  PageHeader,
  StopTimeline,
  StudentRow,
  RosterTable,
  StatCard
} = window.DesignSystem_9e66e1;
const W_STOPS = [{
  name: '한화아파트',
  address: '대치동 316-1',
  time: '8:37',
  state: 'done',
  riders: 4
}, {
  name: '대치사거리',
  address: '대치동 902',
  time: '8:44',
  state: 'current',
  riders: 3,
  missed: 1
}, {
  name: '은마아파트',
  address: '대치동 316',
  time: '8:51',
  state: 'next',
  riders: 5
}, {
  name: '한빛학원 앞',
  address: '대치동 977',
  time: '8:58',
  state: 'upcoming'
}];
const BUSES = [{
  id: '3-1',
  bus: '3-1호차',
  leg: '등원',
  driver: '이강우',
  manager: '한소영',
  stops: 5,
  riders: 14,
  status: 'boarded',
  missed: 0
}, {
  id: '3-2',
  bus: '3-2호차',
  leg: '등원',
  driver: '박정호',
  manager: '김윤정',
  stops: 4,
  riders: 12,
  status: 'moving',
  missed: 1
}, {
  id: '3-3',
  bus: '3-3호차',
  leg: '등원',
  driver: '최민석',
  manager: '오지현',
  stops: 6,
  riders: 17,
  status: 'boarded',
  missed: 0
}, {
  id: '3-4',
  bus: '3-4호차',
  leg: '등원',
  driver: '정해린',
  manager: '배수현',
  stops: 4,
  riders: 11,
  status: 'idle',
  missed: 0
}];
const STUDENTS = [{
  id: 1,
  name: '김하준',
  klass: '3학년 2반',
  stop: '한화아파트',
  bus: '3-2호차',
  guardian: '010-2211-****',
  s: 'boarded',
  change: null,
  attend: '등원'
}, {
  id: 2,
  name: '박수민',
  klass: '4학년 1반',
  stop: '대치사거리',
  bus: '3-2호차',
  guardian: '010-3388-****',
  s: 'missed',
  change: null,
  attend: '등원'
}, {
  id: 3,
  name: '이서연',
  klass: '3학년 1반',
  stop: '은마아파트',
  bus: '3-2호차',
  guardian: '010-7742-****',
  s: 'idle',
  change: null,
  attend: '미등원'
}, {
  id: 4,
  name: '최다은',
  klass: '5학년 3반',
  stop: '선경아파트 정문',
  bus: '3-2호차',
  guardian: '010-9920-****',
  s: 'idle',
  change: 'added',
  attend: '등원'
}, {
  id: 5,
  name: '윤재희',
  klass: '4학년 2반',
  stop: '미도아파트',
  bus: '3-2호차',
  guardian: '010-5561-****',
  s: 'idle',
  change: 'removed',
  attend: '미등원'
}];
const MANAGERS = [{
  id: 1,
  name: '김윤정',
  role: '동승자',
  hours: '07:30 – 09:30 / 19:00 – 21:00',
  phone: '010-4412-****',
  bus: '3-2호차',
  code: 'MG-4K2P'
}, {
  id: 2,
  name: '박정호',
  role: '버스기사',
  hours: '07:00 – 10:00 / 18:30 – 21:30',
  phone: '010-8823-****',
  bus: '3-2호차',
  code: 'DR-9T1A'
}, {
  id: 3,
  name: '한소영',
  role: '동승자',
  hours: '07:30 – 09:30',
  phone: '010-2277-****',
  bus: '3-1호차',
  code: 'MG-7B3Z'
}];
function Dashboard({
  onOpenRun
}) {
  return /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement(PageHeader, {
    title: "\uC6B4\uD589 \uAD00\uB9AC",
    description: "2026\uB144 8\uC6D4 11\uC77C \uD654\uC694\uC77C \xB7 \uB4F1\uC6D0 4\uAC1C \uB178\uC120 \xB7 \uD655\uC815 \uD0D1\uC2B9\uC790 54\uBA85",
    actions: /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement(Button, {
      variant: "secondary",
      icon: "route"
    }, "\uACE0\uC815 \uB178\uC120"), /*#__PURE__*/React.createElement(Button, {
      icon: "plus"
    }, "\uB178\uC120 \uAC15\uC81C \uCD94\uAC00"))
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '22px var(--gutter-desktop) 0',
      display: 'grid',
      gridTemplateColumns: 'repeat(4, 1fr)',
      gap: 14
    }
  }, /*#__PURE__*/React.createElement(StatCard, {
    label: "\uC6B4\uD589 \uC911 \uB178\uC120",
    value: 2,
    unit: "\uAC1C",
    icon: "bus",
    sub: "\uC804\uCCB4 4\uAC1C \uB178\uC120"
  }), /*#__PURE__*/React.createElement(StatCard, {
    label: "\uD0D1\uC2B9 \uC644\uB8CC",
    value: 43,
    unit: "\uBA85",
    tone: "boarded",
    icon: "circle-check",
    sub: "\uD655\uC815 54\uBA85 \uC911"
  }), /*#__PURE__*/React.createElement(StatCard, {
    label: "\uBBF8\uD0D1\uC2B9",
    value: 1,
    unit: "\uBA85",
    tone: "missed",
    icon: "triangle-alert",
    sub: "\uBC15\uC218\uBBFC \xB7 \uB300\uCE58\uC0AC\uAC70\uB9AC"
  }), /*#__PURE__*/React.createElement(StatCard, {
    label: "\uBBF8\uB4F1\uC6D0",
    value: 2,
    unit: "\uBA85",
    icon: "user-round",
    sub: "\uBCF4\uD638\uC790 \uC0AC\uC804 \uD1B5\uBCF4"
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '18px var(--gutter-desktop) 0'
    }
  }, /*#__PURE__*/React.createElement(AlertBanner, {
    tone: "missed",
    title: "\uBC15\uC218\uBBFC \uD559\uC0DD\uC774 \uB300\uCE58\uC0AC\uAC70\uB9AC\uC5D0\uC11C \uD0D1\uC2B9\uD558\uC9C0 \uC54A\uC558\uC5B4\uC694",
    action: /*#__PURE__*/React.createElement("div", {
      style: {
        display: 'flex',
        gap: 8
      }
    }, /*#__PURE__*/React.createElement(Button, {
      variant: "danger",
      size: "sm",
      icon: "phone"
    }, "\uBCF4\uD638\uC790 \uC5F0\uB77D"), /*#__PURE__*/React.createElement(Button, {
      variant: "secondary",
      size: "sm"
    }, "\uBBF8\uB4F1\uC6D0 \uCC98\uB9AC"))
  }, "\uC815\uB958\uC7A5 \uB3C4\uCC29 \uC608\uC815 \uC2DC\uAC04\uC740 8:44\uC600\uC2B5\uB2C8\uB2E4. \uB3D9\uC2B9 \uB9E4\uB2C8\uC800 \uAE40\uC724\uC815\uC5D0\uAC8C\uB3C4 \uD568\uAED8 \uC548\uB0B4\uB410\uC2B5\uB2C8\uB2E4.")), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '20px var(--gutter-desktop) 0',
      display: 'grid',
      gridTemplateColumns: 'minmax(0,1.35fr) minmax(0,1fr)',
      gap: 20,
      alignItems: 'start'
    }
  }, /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement("h3", {
    style: {
      fontSize: 19,
      marginBottom: 12
    }
  }, "\uAE08\uC77C \uC6B4\uD589 \uD604\uD669"), /*#__PURE__*/React.createElement(RosterTable, {
    onRowClick: onOpenRun,
    rows: BUSES,
    columns: [{
      key: 'bus',
      label: '호차',
      width: 92,
      render: r => /*#__PURE__*/React.createElement(Badge, {
        tone: "brand"
      }, r.bus)
    }, {
      key: 'status',
      label: '상태',
      width: 116,
      render: r => /*#__PURE__*/React.createElement(StatusPill, {
        status: r.status,
        showIcon: false
      }, r.status === 'moving' ? '이동 중' : r.status === 'idle' ? '운행 전' : '정상 운행')
    }, {
      key: 'driver',
      label: '기사'
    }, {
      key: 'manager',
      label: '동승 매니저'
    }, {
      key: 'riders',
      label: '탑승자',
      align: 'right',
      width: 80,
      render: r => r.riders + '명'
    }, {
      key: 'missed',
      label: '미탑승',
      align: 'right',
      width: 80,
      render: r => r.missed ? /*#__PURE__*/React.createElement("span", {
        style: {
          color: 'var(--status-missed)',
          fontWeight: 700
        }
      }, r.missed, "\uBA85") : '—'
    }]
  })), /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement("h3", {
    style: {
      fontSize: 19,
      marginBottom: 12
    }
  }, "3-2\uD638\uCC28 \uC2E4\uC2DC\uAC04"), /*#__PURE__*/React.createElement(Card, {
    padding: 0,
    style: {
      overflow: 'hidden'
    }
  }, /*#__PURE__*/React.createElement(MapSurface, {
    height: 168,
    stops: W_STOPS,
    busAt: 0.42
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: 18
    }
  }, /*#__PURE__*/React.createElement(StopTimeline, {
    dense: true,
    stops: W_STOPS
  }))))), /*#__PURE__*/React.createElement("div", {
    style: {
      height: 32
    }
  }));
}
function TodayRun({
  bus,
  setBus
}) {
  return /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement(PageHeader, {
    title: "\uAE08\uC77C \uC6B4\uD589",
    description: "\uCD9C\uBC1C 30\uBD84 \uC804 \uD655\uC815 \xB7 \uAE08\uC77C \uCD94\uAC00\uB294 \uCD08\uB85D, \uC0AD\uC81C\uB294 \uBE68\uAC15\uC73C\uB85C \uD45C\uC2DC\uD569\uB2C8\uB2E4",
    actions: /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement(Button, {
      variant: "secondary",
      icon: "plus"
    }, "\uD0D1\uC2B9\uC790 \uAC15\uC81C \uCD94\uAC00"), /*#__PURE__*/React.createElement(Button, {
      variant: "secondary",
      icon: "list"
    }, "\uBA85\uB2E8 \uB0B4\uB824\uBC1B\uAE30"))
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '20px var(--gutter-desktop) 0',
      display: 'flex',
      gap: 8,
      flexWrap: 'wrap'
    }
  }, BUSES.map(b => /*#__PURE__*/React.createElement("button", {
    key: b.id,
    type: "button",
    onClick: () => setBus(b.id),
    style: {
      height: 40,
      padding: '0 16px',
      cursor: 'pointer',
      borderRadius: 'var(--radius-pill)',
      border: '1px solid ' + (bus === b.id ? 'var(--accent-primary)' : 'var(--border-default)'),
      background: bus === b.id ? 'var(--accent-primary)' : 'var(--surface-card)',
      color: bus === b.id ? 'var(--text-inverse)' : 'var(--text-primary)',
      font: 'var(--fw-medium) var(--fs-label-sm)/1 var(--font-sans)'
    }
  }, b.bus))), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '18px var(--gutter-desktop) 0',
      display: 'grid',
      gridTemplateColumns: 'minmax(0,1fr) 300px',
      gap: 20,
      alignItems: 'start'
    }
  }, /*#__PURE__*/React.createElement(RosterTable, {
    rows: STUDENTS,
    columns: [{
      key: 'name',
      label: '이름',
      width: 96
    }, {
      key: 'klass',
      label: '반',
      width: 96
    }, {
      key: 'stop',
      label: '정류장'
    }, {
      key: 'guardian',
      label: '보호자',
      width: 140
    }, {
      key: 'attend',
      label: '등원 여부',
      width: 96,
      render: r => /*#__PURE__*/React.createElement("span", {
        style: {
          color: r.attend === '미등원' ? 'var(--text-tertiary)' : 'var(--text-primary)'
        }
      }, r.attend)
    }, {
      key: 's',
      label: '탑승 현황',
      width: 116,
      render: r => /*#__PURE__*/React.createElement(StatusPill, {
        status: r.s,
        showIcon: false
      }, r.s === 'boarded' ? '탑승 완료' : r.s === 'missed' ? '미탑승' : '대기')
    }, {
      key: 'change',
      label: '금일 변경',
      width: 106,
      render: r => r.change === 'added' ? /*#__PURE__*/React.createElement(Badge, {
        tone: "added"
      }, "\uCD94\uAC00") : r.change === 'removed' ? /*#__PURE__*/React.createElement(Badge, {
        tone: "removed"
      }, "\uC0AD\uC81C") : '—'
    }]
  }), /*#__PURE__*/React.createElement(Card, {
    padding: 18
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--fw-medium) 14px/1.4 var(--font-sans)'
    }
  }, "\uB178\uC120\uBCC4 \uC815\uB958\uC7A5"), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 14
    }
  }, /*#__PURE__*/React.createElement(StopTimeline, {
    dense: true,
    stops: W_STOPS
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 16,
      paddingTop: 16,
      borderTop: '1px solid var(--border-subtle)'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--fw-light) 13px/1.7 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, "\uAE30\uC0AC \uBC15\uC815\uD638 \xB7 \uB3D9\uC2B9 \uB9E4\uB2C8\uC800 \uAE40\uC724\uC815", /*#__PURE__*/React.createElement("br", null), "\uCD9C\uBC1C 8:30 \xB7 \uB3C4\uCC29 \uC608\uC815 8:58"), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 12
    }
  }, /*#__PURE__*/React.createElement(Button, {
    variant: "secondary",
    size: "sm",
    block: true,
    icon: "pencil"
  }, "\uB9E4\uB2C8\uC800 \uBC30\uCE58 \uBCC0\uACBD"))))), /*#__PURE__*/React.createElement("div", {
    style: {
      height: 32
    }
  }));
}
function Routes() {
  const [sel, setSel] = React.useState('3-2');
  return /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement(PageHeader, {
    title: "\uACE0\uC815 \uB178\uC120",
    description: "\uC5F0\uCD08\xB7\uC2E0\uADDC \uD559\uC0DD \uB4F1\uB85D \uC2DC \uAC31\uC2E0\uD569\uB2C8\uB2E4. \uC815\uB958\uC7A5 \uB2E8\uC704\uB85C \uAD00\uB9AC\uD569\uB2C8\uB2E4",
    actions: /*#__PURE__*/React.createElement(Button, {
      icon: "plus"
    }, "\uC815\uB958\uC7A5 \uCD94\uAC00")
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '20px var(--gutter-desktop) 0',
      display: 'grid',
      gridTemplateColumns: '300px minmax(0,1fr)',
      gap: 20,
      alignItems: 'start'
    }
  }, /*#__PURE__*/React.createElement(Card, {
    padding: 0,
    style: {
      overflow: 'hidden'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '14px 18px',
      background: 'var(--bg-subtle)',
      font: 'var(--fw-medium) 13px/1 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, "\uBC84\uC2A4\uBCC4 \uACE0\uC815 \uB178\uC120"), BUSES.map(b => /*#__PURE__*/React.createElement("button", {
    key: b.id,
    type: "button",
    onClick: () => setSel(b.id),
    style: {
      width: '100%',
      display: 'flex',
      alignItems: 'center',
      gap: 10,
      padding: '14px 18px',
      background: sel === b.id ? 'var(--accent-primary-soft)' : 'transparent',
      border: 'none',
      borderTop: '1px solid var(--border-subtle)',
      cursor: 'pointer',
      textAlign: 'left'
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      font: 'var(--fw-medium) 14px/1.3 var(--font-sans)'
    }
  }, b.bus), /*#__PURE__*/React.createElement("span", {
    style: {
      marginLeft: 'auto',
      font: 'var(--fw-light) 12.5px/1 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, b.stops, "\uAC1C \uC815\uB958\uC7A5 \xB7 ", b.riders, "\uBA85")))), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      flexDirection: 'column',
      gap: 16
    }
  }, /*#__PURE__*/React.createElement(Card, {
    padding: 20
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 10
    }
  }, /*#__PURE__*/React.createElement("h3", {
    style: {
      fontSize: 20
    }
  }, sel, "\uD638\uCC28 \uB4F1\uC6D0 \uB178\uC120"), /*#__PURE__*/React.createElement(Badge, {
    tone: "brand",
    style: {
      marginLeft: 'auto'
    }
  }, "\uCD9C\uBC1C 8:30")), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 16,
      display: 'grid',
      gridTemplateColumns: '1fr 1fr',
      gap: 24
    }
  }, /*#__PURE__*/React.createElement(StopTimeline, {
    stops: W_STOPS
  }), /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--fw-medium) 13px/1 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, "\uBC30\uCE58\uB41C \uB9E4\uB2C8\uC800"), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 12,
      display: 'flex',
      flexDirection: 'column',
      gap: 10
    }
  }, MANAGERS.filter(m => m.bus === sel + '호차').map(m => /*#__PURE__*/React.createElement(Card, {
    key: m.id,
    tone: "outline",
    padding: 14
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 8
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      font: 'var(--fw-medium) 14px/1.3 var(--font-sans)'
    }
  }, m.name), /*#__PURE__*/React.createElement(Badge, null, m.role), /*#__PURE__*/React.createElement(Button, {
    variant: "ghost",
    size: "sm",
    style: {
      marginLeft: 'auto'
    }
  }, "\uBCC0\uACBD")), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 6,
      font: 'var(--fw-light) 12.5px/1.5 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, "\uADFC\uBB34 ", m.hours))))))))), /*#__PURE__*/React.createElement("div", {
    style: {
      height: 32
    }
  }));
}
function Students({
  onAdd
}) {
  const [q, setQ] = React.useState('');
  const [sel, setSel] = React.useState(null);
  const rows = STUDENTS.filter(s => s.name.includes(q));
  return /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement(PageHeader, {
    title: "\uD559\uC0DD \uC815\uBCF4 \uAD00\uB9AC",
    description: "\uC774\uB984\uC73C\uB85C \uAC80\uC0C9\uD558\uACE0, \uD589\uC744 \uB20C\uB7EC \uD559\uC0DD \uC815\uBCF4\uB97C \uD655\uC778\uD569\uB2C8\uB2E4",
    actions: /*#__PURE__*/React.createElement(Button, {
      icon: "plus",
      onClick: onAdd
    }, "\uD559\uC0DD \uCD94\uAC00")
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '20px var(--gutter-desktop) 0',
      maxWidth: 420
    }
  }, /*#__PURE__*/React.createElement(SearchField, {
    value: q,
    onChange: e => setQ(e.target.value),
    placeholder: "\uD559\uC0DD \uC774\uB984"
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '18px var(--gutter-desktop) 0',
      display: 'grid',
      gridTemplateColumns: 'minmax(0,1fr) 320px',
      gap: 20,
      alignItems: 'start'
    }
  }, rows.length ? /*#__PURE__*/React.createElement(RosterTable, {
    onRowClick: setSel,
    rows: rows,
    columns: [{
      key: 'name',
      label: '이름',
      width: 100
    }, {
      key: 'klass',
      label: '반',
      width: 100
    }, {
      key: 'stop',
      label: '탑승 정류장'
    }, {
      key: 'bus',
      label: '호차',
      width: 96
    }, {
      key: 'guardian',
      label: '보호자 연락처',
      width: 150
    }]
  }) : /*#__PURE__*/React.createElement(Card, null, /*#__PURE__*/React.createElement(EmptyState, {
    icon: "search",
    title: "\uAC80\uC0C9 \uACB0\uACFC\uAC00 \uC5C6\uC5B4\uC694"
  }, "\uC774\uB984\uC744 \uB2E4\uC2DC \uD655\uC778\uD574 \uC8FC\uC138\uC694.")), sel ? /*#__PURE__*/React.createElement(Card, {
    padding: 20
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 10
    }
  }, /*#__PURE__*/React.createElement("h3", {
    style: {
      fontSize: 20
    }
  }, sel.name), /*#__PURE__*/React.createElement(IconButton, {
    icon: "x",
    label: "\uB2EB\uAE30",
    style: {
      marginLeft: 'auto'
    },
    onClick: () => setSel(null)
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 14,
      display: 'flex',
      flexDirection: 'column',
      gap: 10
    }
  }, [['반', sel.klass], ['탑승 정류장', sel.stop], ['호차', sel.bus], ['보호자 연락처', sel.guardian], ['등원 여부', sel.attend], ['학부모 코드', 'PR-' + sel.id + 'H8QK']].map(([k, v]) => /*#__PURE__*/React.createElement("div", {
    key: k,
    style: {
      display: 'flex',
      gap: 12,
      font: 'var(--fw-regular) 14px/1.6 var(--font-sans)'
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      width: 96,
      flex: 'none',
      color: 'var(--text-secondary)',
      fontWeight: 300
    }
  }, k), /*#__PURE__*/React.createElement("span", null, v)))), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 16,
      paddingTop: 16,
      borderTop: '1px solid var(--border-subtle)'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--fw-light) 12.5px/1.6 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, "\uD2B9\uC774\uC0AC\uD56D \xB7 \uC218\uC694\uC77C\uC740 \uD560\uBA38\uB2C8\uAC00 \uC740\uB9C8\uC544\uD30C\uD2B8\uB85C \uB370\uB9AC\uB7EC \uC624\uC2ED\uB2C8\uB2E4.")), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 16,
      display: 'flex',
      gap: 8
    }
  }, /*#__PURE__*/React.createElement(Button, {
    variant: "secondary",
    size: "sm",
    icon: "pencil",
    style: {
      flex: 1
    }
  }, "\uAC1C\uC778\uC815\uBCF4 \uC218\uC815"), /*#__PURE__*/React.createElement(Button, {
    variant: "danger",
    size: "sm",
    icon: "trash-2"
  }, "\uC0AD\uC81C"))) : /*#__PURE__*/React.createElement(Card, {
    padding: 20
  }, /*#__PURE__*/React.createElement(EmptyState, {
    icon: "user-round",
    title: "\uD559\uC0DD\uC744 \uC120\uD0DD\uD574 \uC8FC\uC138\uC694"
  }, "\uC67C\uCABD \uD45C\uC5D0\uC11C \uC774\uB984\uC744 \uB204\uB974\uBA74 \uC0C1\uC138 \uC815\uBCF4\uAC00 \uC5F4\uB9BD\uB2C8\uB2E4."))), /*#__PURE__*/React.createElement("div", {
    style: {
      height: 32
    }
  }));
}
function StudentAdd({
  onCancel,
  onSave,
  savedCode
}) {
  return /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement(PageHeader, {
    title: "\uD559\uC0DD \uCD94\uAC00",
    description: "\uC800\uC7A5\uD558\uBA74 \uD559\uBD80\uBAA8 \uCF54\uB4DC\uC640 \uD559\uC0DD \uCF54\uB4DC\uAC00 \uD568\uAED8 \uBC1C\uAE09\uB429\uB2C8\uB2E4"
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '20px var(--gutter-desktop) 0',
      maxWidth: 880
    }
  }, savedCode ? /*#__PURE__*/React.createElement("div", {
    style: {
      marginBottom: 18
    }
  }, /*#__PURE__*/React.createElement(AlertBanner, {
    tone: "boarded",
    title: "\uD559\uC0DD \uC815\uBCF4\uAC00 \uCD94\uAC00\uB410\uC2B5\uB2C8\uB2E4"
  }, "\uD559\uBD80\uBAA8 \uCF54\uB4DC ", /*#__PURE__*/React.createElement("b", null, savedCode), " \xB7 \uD559\uC0DD \uCF54\uB4DC ", /*#__PURE__*/React.createElement("b", null, "ST-", savedCode.slice(-4)), " \u2014 \uBCF4\uD638\uC790\uC5D0\uAC8C \uC804\uB2EC\uD574 \uC8FC\uC138\uC694.")) : null, /*#__PURE__*/React.createElement(Card, {
    padding: 24
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'grid',
      gridTemplateColumns: '1fr 1fr',
      gap: 16
    }
  }, /*#__PURE__*/React.createElement(Input, {
    label: "\uD559\uC0DD \uC774\uB984",
    placeholder: "\uC608: \uAE40\uD558\uC900",
    required: true
  }), /*#__PURE__*/React.createElement(Input, {
    label: "\uC601\uBB38 \uC774\uB984 (\uC120\uD0DD)",
    placeholder: "Kim Hajun"
  }), /*#__PURE__*/React.createElement(Input, {
    label: "\uC0DD\uB144\uC6D4\uC77C",
    placeholder: "2016-03-04",
    required: true
  }), /*#__PURE__*/React.createElement(Input, {
    label: "\uBCF4\uD638\uC790 \uC804\uD654\uBC88\uD638",
    icon: "phone",
    placeholder: "010-0000-0000",
    required: true
  }), /*#__PURE__*/React.createElement(Input, {
    label: "\uD559\uC6D0 \uBC18",
    placeholder: "3\uD559\uB144 2\uBC18"
  }), /*#__PURE__*/React.createElement(Select, {
    label: "\uBC30\uC815 \uD638\uCC28",
    options: ['3-1호차', '3-2호차', '3-3호차', '3-4호차']
  }), /*#__PURE__*/React.createElement(Input, {
    label: "\uC8FC\uC18C",
    placeholder: "\uC11C\uC6B8 \uAC15\uB0A8\uAD6C \uB300\uCE58\uB3D9 316-1",
    wrapStyle: {
      gridColumn: '1 / -1'
    }
  }), /*#__PURE__*/React.createElement(Textarea, {
    label: "\uD2B9\uC774\uC0AC\uD56D",
    wrapStyle: {
      gridColumn: '1 / -1'
    },
    hint: "\uC608: \uC218\uC694\uC77C\uC740 \uD560\uBA38\uB2C8\uAC00 \uC740\uB9C8\uC544\uD30C\uD2B8\uB85C \uB370\uB9AC\uB7EC \uC624\uC2ED\uB2C8\uB2E4",
    rows: 3
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 22,
      paddingTop: 20,
      borderTop: '1px solid var(--border-subtle)'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--fw-medium) 14px/1.4 var(--font-sans)'
    }
  }, "\uC694\uC77C\uBCC4 \uD558\uC6D0 \uC704\uCE58\uAC00 \uB2E4\uB97C \uB54C"), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 12,
      display: 'grid',
      gridTemplateColumns: '160px 1fr',
      gap: 12
    }
  }, /*#__PURE__*/React.createElement(Select, {
    options: ['월요일', '화요일', '수요일', '목요일', '금요일']
  }), /*#__PURE__*/React.createElement(Input, {
    placeholder: "\uD2B9\uC815 \uC694\uC77C \uD558\uC6D0 \uC8FC\uC18C"
  }))), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 22,
      display: 'flex',
      gap: 8,
      justifyContent: 'flex-end'
    }
  }, /*#__PURE__*/React.createElement(Button, {
    variant: "ghost",
    onClick: onCancel
  }, "\uCDE8\uC18C"), /*#__PURE__*/React.createElement(Button, {
    onClick: onSave
  }, "\uC800\uC7A5")))), /*#__PURE__*/React.createElement("div", {
    style: {
      height: 32
    }
  }));
}
function Managers() {
  const [q, setQ] = React.useState('');
  const [del, setDel] = React.useState(null);
  const rows = MANAGERS.filter(m => m.name.includes(q));
  return /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'relative'
    }
  }, /*#__PURE__*/React.createElement(PageHeader, {
    title: "\uB9E4\uB2C8\uC800 \uAD00\uB9AC",
    description: "\uBC84\uC2A4\uAE30\uC0AC\uC640 \uB3D9\uC2B9\uC790\uB97C \uB4F1\uB85D\uD558\uACE0 \uADFC\uBB34 \uC2DC\uAC04\uC744 \uAD00\uB9AC\uD569\uB2C8\uB2E4",
    actions: /*#__PURE__*/React.createElement(Button, {
      icon: "plus"
    }, "\uB9E4\uB2C8\uC800 \uCD94\uAC00")
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '20px var(--gutter-desktop) 0',
      maxWidth: 420
    }
  }, /*#__PURE__*/React.createElement(SearchField, {
    value: q,
    onChange: e => setQ(e.target.value),
    placeholder: "\uB9E4\uB2C8\uC800 \uC774\uB984"
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '18px var(--gutter-desktop) 0'
    }
  }, /*#__PURE__*/React.createElement(RosterTable, {
    rows: rows,
    columns: [{
      key: 'name',
      label: '이름',
      width: 100
    }, {
      key: 'role',
      label: '역할',
      width: 110,
      render: r => /*#__PURE__*/React.createElement(Badge, {
        tone: r.role === '버스기사' ? 'brand' : 'neutral'
      }, r.role)
    }, {
      key: 'hours',
      label: '근무 시간'
    }, {
      key: 'phone',
      label: '전화번호',
      width: 150
    }, {
      key: 'bus',
      label: '배치',
      width: 100
    }, {
      key: 'code',
      label: '발급 코드',
      width: 120
    }, {
      key: 'act',
      label: '',
      width: 130,
      align: 'right',
      render: r => /*#__PURE__*/React.createElement("div", {
        style: {
          display: 'flex',
          gap: 6,
          justifyContent: 'flex-end'
        }
      }, /*#__PURE__*/React.createElement(Button, {
        variant: "ghost",
        size: "sm"
      }, "\uC218\uC815"), /*#__PURE__*/React.createElement(Button, {
        variant: "ghost",
        size: "sm",
        onClick: () => setDel(r),
        style: {
          color: 'var(--status-missed)'
        }
      }, "\uC0AD\uC81C"))
    }]
  })), /*#__PURE__*/React.createElement(Dialog, {
    open: !!del,
    title: del ? del.name + ' 매니저를 삭제할까요?' : '',
    onClose: () => setDel(null),
    footer: /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement(Button, {
      variant: "ghost",
      onClick: () => setDel(null)
    }, "\uCDE8\uC18C"), /*#__PURE__*/React.createElement(Button, {
      variant: "danger",
      onClick: () => setDel(null)
    }, "\uC0AD\uC81C"))
  }, "\uBC30\uCE58\uB41C \uB178\uC120\uC5D0\uC11C \uD568\uAED8 \uD574\uC81C\uB418\uACE0 \uBC1C\uAE09 \uCF54\uB4DC\uB294 \uC989\uC2DC \uC0AC\uC6A9\uD560 \uC218 \uC5C6\uAC8C \uB429\uB2C8\uB2E4."), /*#__PURE__*/React.createElement("div", {
    style: {
      height: 32
    }
  }));
}
function Logs() {
  const LOGS = [{
    t: '8:44',
    k: 'missed',
    title: '박수민 미탑승',
    body: '대치사거리 · 3-2호차 · 보호자 알림 전송',
    who: '자동'
  }, {
    t: '8:41',
    k: 'moving',
    title: '도착 지연 알림 전송',
    body: '도로 정체 · 5분 · 남은 정류장 2곳 보호자 8명',
    who: '김윤정 (동승자)'
  }, {
    t: '8:37',
    k: 'boarded',
    title: '김하준 승차',
    body: '한화아파트 · 3-2호차',
    who: '김윤정 (동승자)'
  }, {
    t: '8:30',
    k: 'boarded',
    title: '3-2호차 운행 시작',
    body: '확정 탑승자 12명 · 정류장 4곳',
    who: '박정호 (기사)'
  }, {
    t: '8:02',
    k: 'idle',
    title: '이서연 미등원 처리',
    body: '보호자 사전 통보 · 등원 제외',
    who: '보호자 앱'
  }];
  return /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement(PageHeader, {
    title: "\uC54C\uB9BC \uB85C\uADF8",
    description: "\uC804\uC1A1\uB41C \uC54C\uB9BC\uACFC \uCC98\uB9AC \uB0B4\uC5ED\uC744 \uC2DC\uAC04 \uC5ED\uC21C\uC73C\uB85C \uBD05\uB2C8\uB2E4",
    actions: /*#__PURE__*/React.createElement(Select, {
      options: ['오늘', '이번 주', '이번 달'],
      wrapStyle: {
        width: 140
      }
    })
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '20px var(--gutter-desktop) 0',
      maxWidth: 880,
      display: 'flex',
      flexDirection: 'column',
      gap: 10
    }
  }, LOGS.map((l, i) => /*#__PURE__*/React.createElement(Card, {
    key: i,
    padding: 16
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 12
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      font: 'var(--fw-bold) 15px/1 var(--font-sans)',
      fontVariantNumeric: 'tabular-nums',
      width: 46,
      color: 'var(--text-secondary)'
    }
  }, l.t), /*#__PURE__*/React.createElement(StatusPill, {
    status: l.k,
    showIcon: false
  }, l.k === 'missed' ? '미탑승' : l.k === 'moving' ? '지연' : l.k === 'idle' ? '미등원' : '정상'), /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1,
      minWidth: 0
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--fw-medium) 14px/1.4 var(--font-sans)'
    }
  }, l.title), /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--fw-light) 12.5px/1.5 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, l.body)), /*#__PURE__*/React.createElement("span", {
    style: {
      font: 'var(--fw-light) 12.5px/1 var(--font-sans)',
      color: 'var(--text-tertiary)'
    }
  }, l.who))))), /*#__PURE__*/React.createElement("div", {
    style: {
      height: 32
    }
  }));
}
Object.assign(window, {
  WebDashboard: Dashboard,
  WebTodayRun: TodayRun,
  WebRoutes: Routes,
  WebStudents: Students,
  WebStudentAdd: StudentAdd,
  WebManagers: Managers,
  WebLogs: Logs
});
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/academy-web/WebScreens.jsx", error: String((e && e.message) || e) }); }

// ui_kits/academy-web/kit.jsx
try { (() => {
const {
  SideNav,
  IconButton,
  SegmentedControl,
  Badge,
  Icon
} = window.DesignSystem_9e66e1;
const NAV = [{
  value: 'dash',
  label: '운행 관리',
  icon: 'layout-dashboard'
}, {
  value: 'today',
  label: '금일 운행',
  icon: 'bus'
}, {
  value: 'routes',
  label: '고정 노선',
  icon: 'route'
}, {
  value: 'students',
  label: '학생 정보 관리',
  icon: 'users-round'
}, {
  value: 'managers',
  label: '매니저 관리',
  icon: 'user-round'
}, {
  value: 'logs',
  label: '알림 로그',
  icon: 'bell',
  badge: 2
}];
function AcademyWeb({
  width
}) {
  const [nav, setNav] = React.useState('dash');
  const [bus, setBus] = React.useState('3-2');
  const [adding, setAdding] = React.useState(false);
  const [saved, setSaved] = React.useState(null);
  let view;
  if (nav === 'dash') view = /*#__PURE__*/React.createElement(WebDashboard, {
    onOpenRun: () => setNav('today')
  });else if (nav === 'today') view = /*#__PURE__*/React.createElement(WebTodayRun, {
    bus: bus,
    setBus: setBus
  });else if (nav === 'routes') view = /*#__PURE__*/React.createElement(WebRoutes, null);else if (nav === 'students') view = adding ? /*#__PURE__*/React.createElement(WebStudentAdd, {
    savedCode: saved,
    onCancel: () => {
      setAdding(false);
      setSaved(null);
    },
    onSave: () => setSaved('PR-7H8QK2')
  }) : /*#__PURE__*/React.createElement(WebStudents, {
    onAdd: () => {
      setAdding(true);
      setSaved(null);
    }
  });else if (nav === 'managers') view = /*#__PURE__*/React.createElement(WebManagers, null);else view = /*#__PURE__*/React.createElement(WebLogs, null);
  return /*#__PURE__*/React.createElement("div", {
    style: {
      width,
      height: 860,
      display: 'flex',
      overflow: 'hidden',
      borderRadius: 14,
      boxShadow: 'var(--shadow-raised)',
      background: 'var(--bg-base)'
    }
  }, /*#__PURE__*/React.createElement(SideNav, {
    academy: "\uB300\uCE58 \uD55C\uBE5B\uD559\uC6D0",
    items: NAV,
    value: nav,
    onChange: v => {
      setNav(v);
      setAdding(false);
      setSaved(null);
    },
    style: {
      width: width < 1200 ? 208 : 248
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1,
      minWidth: 0,
      display: 'flex',
      flexDirection: 'column'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 12,
      height: 56,
      padding: '0 var(--gutter-desktop)',
      borderBottom: '1px solid var(--border-subtle)',
      background: 'var(--surface-card)'
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      font: 'var(--fw-light) 13px/1 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, "2026\uB144 8\uC6D4 11\uC77C \uD654\uC694\uC77C \xB7 \uB4F1\uC6D0 \uC6B4\uD589 \uC911"), /*#__PURE__*/React.createElement("div", {
    style: {
      marginLeft: 'auto',
      display: 'flex',
      alignItems: 'center',
      gap: 8
    }
  }, /*#__PURE__*/React.createElement(Badge, {
    tone: "red",
    count: 2
  }), /*#__PURE__*/React.createElement(IconButton, {
    icon: "bell",
    label: "\uC54C\uB9BC"
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 8,
      paddingLeft: 8,
      borderLeft: '1px solid var(--border-subtle)'
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      width: 32,
      height: 32,
      borderRadius: 999,
      background: 'var(--bg-subtle)',
      color: 'var(--text-brand)',
      display: 'grid',
      placeItems: 'center',
      font: 'var(--fw-bold) 12px var(--font-sans)'
    }
  }, "\uC6D0\uC7A5"), /*#__PURE__*/React.createElement("span", {
    style: {
      font: 'var(--fw-medium) 13px/1 var(--font-sans)'
    }
  }, "\uC815\uD61C\uB780")))), /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1,
      minHeight: 0,
      overflowY: 'auto'
    }
  }, view)));
}
function WebKitRoot() {
  const [w, setW] = React.useState(1440);
  return /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 16,
      marginBottom: 18
    }
  }, /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--fw-bold) 11px/1 var(--font-sans)',
      letterSpacing: '.16em',
      color: 'var(--text-tertiary)'
    }
  }, "ACADEMY STAFF WEB"), /*#__PURE__*/React.createElement("h2", {
    style: {
      marginTop: 8,
      fontSize: 26
    }
  }, "\uD559\uC6D0 \uAD00\uACC4\uC790 \uC6F9")), /*#__PURE__*/React.createElement("div", {
    style: {
      marginLeft: 'auto',
      width: 240
    }
  }, /*#__PURE__*/React.createElement(SegmentedControl, {
    block: true,
    value: String(w),
    onChange: v => setW(Number(v)),
    options: [{
      value: '1440',
      label: '1440'
    }, {
      value: '1120',
      label: '1120'
    }]
  }))), /*#__PURE__*/React.createElement(AcademyWeb, {
    width: w
  }));
}
ReactDOM.createRoot(document.getElementById('root')).render(/*#__PURE__*/React.createElement(WebKitRoot, null));
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/academy-web/kit.jsx", error: String((e && e.message) || e) }); }

// ui_kits/manager-app/ManagerScreens.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
const {
  Button,
  IconButton,
  Card,
  StatusPill,
  Badge,
  Icon,
  Input,
  Textarea,
  Select,
  SegmentedControl,
  CodeInput,
  Checkbox,
  AlertBanner,
  BottomSheet,
  EmptyState,
  Dialog,
  AppHeader,
  TabBar,
  StopTimeline,
  StudentRow,
  RunSummaryCard,
  DelayPicker,
  StatCard
} = window.DesignSystem_9e66e1;
const M_STOPS = [{
  name: '한화아파트',
  address: '대치동 316-1',
  time: '8:37',
  state: 'done',
  riders: 4
}, {
  name: '대치사거리',
  address: '대치동 902',
  time: '8:44',
  state: 'current',
  riders: 3,
  missed: 1
}, {
  name: '은마아파트',
  address: '대치동 316',
  time: '8:51',
  state: 'next',
  riders: 5
}, {
  name: '한빛학원 앞',
  address: '대치동 977',
  time: '8:58',
  state: 'upcoming',
  riders: 0
}];
const ROSTER = [{
  name: '김하준',
  meta: '3학년 2반 · 보호자 010-2211-****',
  ride: 'boarded'
}, {
  name: '박수민',
  meta: '4학년 1반 · 보호자 010-3388-****',
  ride: 'waiting'
}, {
  name: '이서연',
  meta: '3학년 1반 · 보호자 010-7742-****',
  ride: 'absent'
}];
function ManagerLogin({
  role,
  setRole,
  code,
  setCode,
  onSubmit,
  error
}) {
  return /*#__PURE__*/React.createElement("div", {
    style: {
      height: '100%',
      background: 'var(--bg-base)',
      display: 'flex',
      flexDirection: 'column',
      padding: '56px 22px 26px'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--fw-bold) 11px/1 var(--font-sans)',
      letterSpacing: '.18em',
      color: 'var(--accent-primary)'
    }
  }, "BARAEDA MANAGER"), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 14,
      font: 'var(--fw-bold) 30px/1.3 var(--font-serif)',
      letterSpacing: '-.02em',
      color: 'var(--text-primary)'
    }
  }, "\uC624\uB298 \uC6B4\uD589\uC744", /*#__PURE__*/React.createElement("br", null), "\uC2DC\uC791\uD569\uB2C8\uB2E4"), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 10,
      font: 'var(--fw-light) 14px/1.7 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, "\uBD80\uC5EC\uB41C \uCF54\uB4DC\uB85C \uB85C\uADF8\uC778\uD558\uBA74 \uB2F4\uB2F9 \uB178\uC120\uC774 \uBC14\uB85C \uC5F4\uB9BD\uB2C8\uB2E4."), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 30
    }
  }, /*#__PURE__*/React.createElement(SegmentedControl, {
    options: ['버스기사', '동승자'],
    value: role,
    onChange: setRole,
    block: true
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 18
    }
  }, /*#__PURE__*/React.createElement(CodeInput, {
    label: role + ' 코드',
    value: code,
    onChange: setCode,
    error: error,
    hint: error ? undefined : '학원에서 받은 6자리 코드를 입력해 주세요'
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 'auto'
    }
  }, /*#__PURE__*/React.createElement(Button, {
    size: "lg",
    block: true,
    onClick: onSubmit,
    disabled: code.length < 6
  }, "\uB85C\uADF8\uC778")));
}
function ManagerHome({
  role,
  onOpenDrive,
  onOpenStop
}) {
  return /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '18px 20px 0'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--fw-light) 13px/1.4 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, "8\uC6D4 11\uC77C \uD654\uC694\uC77C \xB7 \uB4F1\uC6D0"), /*#__PURE__*/React.createElement("h2", {
    style: {
      marginTop: 4,
      fontSize: 25
    }
  }, "\uB2F4\uB2F9 \uC6B4\uD589 \uB178\uC120")), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '16px 20px 0',
      display: 'flex',
      gap: 10
    }
  }, /*#__PURE__*/React.createElement(StatCard, {
    style: {
      flex: 1
    },
    label: "\uD655\uC815 \uD0D1\uC2B9\uC790",
    value: 12,
    unit: "\uBA85",
    icon: "users-round",
    sub: "\uC815\uB958\uC7A5 4\uACF3"
  }), /*#__PURE__*/React.createElement(StatCard, {
    style: {
      flex: 1
    },
    label: "\uBBF8\uD0D1\uC2B9",
    value: 1,
    unit: "\uBA85",
    tone: "missed",
    icon: "triangle-alert"
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '12px 20px 0'
    }
  }, /*#__PURE__*/React.createElement(Card, {
    padding: 18,
    onClick: onOpenDrive
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 8
    }
  }, /*#__PURE__*/React.createElement(Badge, {
    tone: "brand"
  }, "3-2\uD638\uCC28"), /*#__PURE__*/React.createElement(StatusPill, {
    status: "moving"
  }, "\uC774\uB3D9 \uC911"), /*#__PURE__*/React.createElement("span", {
    style: {
      marginLeft: 'auto',
      color: 'var(--text-tertiary)'
    }
  }, /*#__PURE__*/React.createElement(Icon, {
    name: "chevron-right",
    size: 18
  }))), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 12,
      font: 'var(--fw-bold) 21px/1.35 var(--font-serif)',
      letterSpacing: '-.015em'
    }
  }, "\uB4F1\uC6D0 \xB7 \uD55C\uD654\uC544\uD30C\uD2B8 \u2192 \uD55C\uBE5B\uD559\uC6D0"), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 6,
      font: 'var(--fw-light) 13px/1.6 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, "\uCD9C\uBC1C 8:30 \xB7 \uAE30\uC0AC \uBC15\uC815\uD638 \xB7 \uB3D9\uC2B9 \uB9E4\uB2C8\uC800 \uAE40\uC724\uC815"))), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '20px 20px 8px'
    }
  }, /*#__PURE__*/React.createElement("h3", {
    style: {
      fontSize: 18
    }
  }, "\uC815\uB958\uC7A5 \uB9AC\uC2A4\uD2B8"), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 4,
      font: 'var(--fw-light) 13px/1.6 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, "\uC815\uB958\uC7A5\uC744 \uB204\uB974\uBA74 \uD0D1\uC2B9\uC790 \uBA85\uB2E8\uC774 \uC5F4\uB9BD\uB2C8\uB2E4.")), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '8px 20px 24px'
    }
  }, /*#__PURE__*/React.createElement(Card, {
    padding: 18
  }, /*#__PURE__*/React.createElement(StopTimeline, {
    stops: M_STOPS,
    onSelect: onOpenStop
  }))), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '0 20px 26px'
    }
  }, /*#__PURE__*/React.createElement(Button, {
    size: "lg",
    block: true,
    icon: "navigation",
    onClick: onOpenDrive
  }, "\uC6B4\uD589\uBAA8\uB4DC \uC2DC\uC791")));
}
function DriveMode({
  onSendArrive,
  onOpenDelay,
  sent
}) {
  return /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement(MapSurface, {
    height: 190,
    dark: true,
    stops: M_STOPS,
    busAt: 0.42
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '16px 20px 0'
    }
  }, sent ? /*#__PURE__*/React.createElement("div", {
    style: {
      marginBottom: 12
    }
  }, /*#__PURE__*/React.createElement(AlertBanner, {
    tone: "boarded",
    title: "\uB3C4\uCC29 \uC54C\uB9BC\uC744 \uBCF4\uB0C8\uC2B5\uB2C8\uB2E4"
  }, "\uC740\uB9C8\uC544\uD30C\uD2B8 \uD0D1\uC2B9\uC790 5\uBA85\uC758 \uBCF4\uD638\uC790\uC5D0\uAC8C \uC804\uC1A1\uB410\uC2B5\uB2C8\uB2E4.")) : null, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'grid',
      gridTemplateColumns: '1fr 1fr',
      gap: 10
    }
  }, /*#__PURE__*/React.createElement(Card, {
    tone: "mist",
    padding: 16
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 5,
      font: 'var(--fw-medium) 12px/1 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, /*#__PURE__*/React.createElement(Icon, {
    name: "navigation",
    size: 13
  }), "\uD604\uC7AC \uC774\uB3D9 \uC911"), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 8,
      font: 'var(--fw-bold) 18px/1.3 var(--font-sans)'
    }
  }, "\uB300\uCE58\uC0AC\uAC70\uB9AC"), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 3,
      font: 'var(--fw-light) 12px/1.5 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, "\uB300\uCE58\uB3D9 902")), /*#__PURE__*/React.createElement(Card, {
    tone: "mist",
    padding: 16
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 5,
      font: 'var(--fw-medium) 12px/1 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, /*#__PURE__*/React.createElement(Icon, {
    name: "map-pin",
    size: 13
  }), "\uB2E4\uC74C \uC815\uB958\uC7A5"), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 8,
      font: 'var(--fw-bold) 18px/1.3 var(--font-sans)'
    }
  }, "\uC740\uB9C8\uC544\uD30C\uD2B8"), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 3,
      font: 'var(--fw-light) 12px/1.5 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, "\uB300\uCE58\uB3D9 316")))), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '14px 20px 0',
      display: 'flex',
      flexDirection: 'column',
      gap: 10
    }
  }, /*#__PURE__*/React.createElement(Button, {
    size: "lg",
    block: true,
    icon: "bell",
    onClick: onSendArrive
  }, "\uB3C4\uCC29 \uC54C\uB9BC \uC804\uC1A1"), /*#__PURE__*/React.createElement(Button, {
    size: "lg",
    block: true,
    variant: "secondary",
    icon: "clock",
    onClick: onOpenDelay
  }, "\uB3C4\uCC29 \uC9C0\uC5F0 \uC54C\uB9BC \uC804\uC1A1")), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '22px 20px 8px'
    }
  }, /*#__PURE__*/React.createElement("h3", {
    style: {
      fontSize: 17
    }
  }, "\uB2E4\uC74C \uC815\uB958\uC7A5 \uD0D1\uC2B9 \uBA85\uB2E8")), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '4px 20px 26px'
    }
  }, /*#__PURE__*/React.createElement(Card, {
    padding: 0,
    style: {
      overflow: 'hidden'
    }
  }, ROSTER.map((r, i) => /*#__PURE__*/React.createElement(StudentRow, _extends({
    key: r.name
  }, r, {
    style: i === ROSTER.length - 1 ? {
      borderBottom: 'none'
    } : null
  }))))));
}
function StopRoster({
  onBulk
}) {
  const [rows, setRows] = React.useState(ROSTER.map(r => ({
    ...r
  })));
  const set = (i, ride) => setRows(rs => rs.map((r, k) => k === i ? {
    ...r,
    ride
  } : r));
  const rideBtn = (i, r) => /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      gap: 4
    }
  }, [['boarded', '탑승'], ['absent', '미등원'], ['alighted', '하차']].map(([v, l]) => /*#__PURE__*/React.createElement("button", {
    key: v,
    type: "button",
    onClick: () => set(i, v),
    style: {
      height: 32,
      padding: '0 10px',
      cursor: 'pointer',
      borderRadius: 'var(--radius-sm)',
      border: '1px solid ' + (r.ride === v ? 'var(--accent-primary)' : 'var(--border-subtle)'),
      background: r.ride === v ? 'var(--accent-primary-soft)' : 'transparent',
      color: r.ride === v ? 'var(--text-brand)' : 'var(--text-secondary)',
      font: 'var(--fw-medium) 12px/1 var(--font-sans)'
    }
  }, l)));
  return /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '18px 20px 0'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 8
    }
  }, /*#__PURE__*/React.createElement("h2", {
    style: {
      fontSize: 23
    }
  }, "\uB300\uCE58\uC0AC\uAC70\uB9AC"), /*#__PURE__*/React.createElement(StatusPill, {
    status: "moving",
    style: {
      marginLeft: 'auto'
    }
  }, "\uC774\uB3D9 \uC911")), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 6,
      font: 'var(--fw-light) 13px/1.6 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, "\uB300\uCE58\uB3D9 902 \xB7 \uB3C4\uCC29 \uC608\uC815 8:44 \xB7 \uD655\uC815 \uD0D1\uC2B9\uC790 3\uBA85")), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '14px 20px 0',
      display: 'flex',
      gap: 8
    }
  }, /*#__PURE__*/React.createElement(Button, {
    variant: "soft",
    style: {
      flex: 1
    },
    onClick: () => setRows(rs => rs.map(r => ({
      ...r,
      ride: 'boarded'
    })))
  }, "\uC77C\uAD04 \uD0D1\uC2B9"), /*#__PURE__*/React.createElement(Button, {
    variant: "secondary",
    style: {
      flex: 1
    },
    onClick: () => setRows(rs => rs.map(r => ({
      ...r,
      ride: 'alighted'
    })))
  }, "\uC77C\uAD04 \uD558\uCC28")), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '14px 20px 26px'
    }
  }, /*#__PURE__*/React.createElement(Card, {
    padding: 0,
    style: {
      overflow: 'hidden'
    }
  }, rows.map((r, i) => /*#__PURE__*/React.createElement(StudentRow, {
    key: r.name,
    name: r.name,
    meta: r.meta,
    ride: r.ride,
    actions: rideBtn(i, r),
    style: i === rows.length - 1 ? {
      borderBottom: 'none'
    } : null
  }))), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 14
    }
  }, /*#__PURE__*/React.createElement(AlertBanner, {
    tone: "missed",
    title: "\uBC15\uC218\uBBFC \uD559\uC0DD\uC774 \uC544\uC9C1 \uD0C0\uC9C0 \uC54A\uC558\uC5B4\uC694",
    action: /*#__PURE__*/React.createElement(Button, {
      variant: "danger",
      size: "sm",
      icon: "phone"
    }, "\uBCF4\uD638\uC790\uC5D0\uAC8C \uC5F0\uB77D")
  }, "\uC815\uB958\uC7A5 \uB3C4\uCC29 \uC608\uC815 \uC2DC\uAC04\uC740 8:44\uC785\uB2C8\uB2E4."))));
}
function DelayScreen({
  onSend
}) {
  const [min, setMin] = React.useState(null);
  const [reason, setReason] = React.useState('도로 정체');
  return /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '18px 20px 26px'
    }
  }, /*#__PURE__*/React.createElement("h2", {
    style: {
      fontSize: 23
    }
  }, "\uB3C4\uCC29 \uC9C0\uC5F0 \uC54C\uB9BC"), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 8,
      font: 'var(--fw-light) 14px/1.7 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, "\uB0A8\uC740 \uC815\uB958\uC7A5 \uD0D1\uC2B9\uC790\uC758 \uBCF4\uD638\uC790\uC5D0\uAC8C \uC9C0\uC5F0 \uC2DC\uAC04\uACFC \uC0AC\uC720\uAC00 \uD568\uAED8 \uC804\uC1A1\uB429\uB2C8\uB2E4."), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 20
    }
  }, /*#__PURE__*/React.createElement(Select, {
    label: "\uC9C0\uC5F0 \uC0AC\uC720",
    options: ['도로 정체', '기상 상황', '차량 점검', '이전 정류장 대기'],
    value: reason,
    onChange: e => setReason(e.target.value)
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 18
    }
  }, /*#__PURE__*/React.createElement(DelayPicker, {
    value: min,
    onChange: setMin
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 18
    }
  }, /*#__PURE__*/React.createElement(Card, {
    tone: "mist",
    padding: 16
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--fw-medium) 12px/1 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, "\uBCF4\uD638\uC790\uC5D0\uAC8C \uC774\uB807\uAC8C \uAC11\uB2C8\uB2E4"), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 8,
      font: 'var(--fw-regular) 15px/1.7 var(--font-sans)'
    }
  }, min ? '약 ' + min + '분 후 도착합니다. ' + reason + '으로 3-2호차가 ' + min + '분 늦어지고 있습니다.' : '지연 시간을 선택하면 문구가 만들어집니다.'))), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 20
    }
  }, /*#__PURE__*/React.createElement(Button, {
    size: "lg",
    block: true,
    disabled: !min,
    onClick: onSend
  }, "\uC9C0\uC5F0 \uC54C\uB9BC \uC804\uC1A1")));
}
function RouteMapScreen() {
  return /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement(MapSurface, {
    height: 300,
    dark: true,
    stops: M_STOPS,
    busAt: 0.42
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '18px 20px 0'
    }
  }, /*#__PURE__*/React.createElement("h3", {
    style: {
      fontSize: 19
    }
  }, "\uAE08\uC77C \uD655\uC815 \uB178\uC120"), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 6,
      font: 'var(--fw-light) 13px/1.6 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, "\uCD9C\uBC1C 30\uBD84 \uC804\uC5D0 \uD655\uC815\uB429\uB2C8\uB2E4. \uBCC0\uACBD\uBD84\uC740 \uC0C9\uC73C\uB85C \uAD6C\uBD84\uD569\uB2C8\uB2E4.")), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '14px 20px 26px',
      display: 'flex',
      flexDirection: 'column',
      gap: 10
    }
  }, /*#__PURE__*/React.createElement(Card, {
    padding: 16,
    accent: "boarded"
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 8
    }
  }, /*#__PURE__*/React.createElement(Badge, {
    tone: "added"
  }, "\uC2E0\uADDC \uCD94\uAC00"), /*#__PURE__*/React.createElement("span", {
    style: {
      marginLeft: 'auto',
      font: 'var(--fw-bold) 13px/1 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, "8:47")), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 8,
      font: 'var(--fw-medium) 15px/1.4 var(--font-sans)'
    }
  }, "\uC120\uACBD\uC544\uD30C\uD2B8 \uC815\uBB38"), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 2,
      font: 'var(--fw-light) 12.5px/1.5 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, "\uAC15\uC81C \uCD94\uAC00 \xB7 1\uBA85 \xB7 \uB300\uCE58\uB3D9 908")), /*#__PURE__*/React.createElement(Card, {
    padding: 16,
    accent: "missed"
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 8
    }
  }, /*#__PURE__*/React.createElement(Badge, {
    tone: "removed"
  }, "\uAE08\uC77C \uC0AD\uC81C"), /*#__PURE__*/React.createElement("span", {
    style: {
      marginLeft: 'auto',
      font: 'var(--fw-bold) 13px/1 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, "\u2014")), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 8,
      font: 'var(--fw-medium) 15px/1.4 var(--font-sans)',
      textDecoration: 'line-through',
      color: 'var(--text-secondary)'
    }
  }, "\uBBF8\uB3C4\uC544\uD30C\uD2B8"), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 2,
      font: 'var(--fw-light) 12.5px/1.5 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, "\uD0D1\uC2B9\uC790 \uC804\uC6D0 \uBBF8\uB4F1\uC6D0"))));
}
function RunEndScreen() {
  return /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '18px 20px 26px'
    }
  }, /*#__PURE__*/React.createElement("h2", {
    style: {
      fontSize: 24
    }
  }, "\uC624\uB298 \uC6B4\uD589\uC744 \uB9C8\uCCE4\uC2B5\uB2C8\uB2E4"), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 8,
      font: 'var(--fw-light) 14px/1.7 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, "8\uC6D4 11\uC77C \uB4F1\uC6D0 \xB7 3-2\uD638\uCC28 \xB7 8:30 \uCD9C\uBC1C / 9:02 \uB3C4\uCC29"), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 18,
      display: 'flex',
      gap: 10
    }
  }, /*#__PURE__*/React.createElement(StatCard, {
    style: {
      flex: 1
    },
    label: "\uD0D1\uC2B9 \uC644\uB8CC",
    value: 11,
    unit: "\uBA85",
    tone: "boarded",
    icon: "circle-check"
  }), /*#__PURE__*/React.createElement(StatCard, {
    style: {
      flex: 1
    },
    label: "\uBBF8\uB4F1\uC6D0",
    value: 1,
    unit: "\uBA85",
    icon: "user-round"
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 12
    }
  }, /*#__PURE__*/React.createElement(StatCard, {
    label: "\uBBF8\uD0D1\uC2B9",
    value: 0,
    unit: "\uBA85",
    icon: "triangle-alert",
    sub: "\uBBF8\uD0D1\uC2B9 \uC5C6\uC774 \uC885\uB8CC\uB410\uC2B5\uB2C8\uB2E4"
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 18
    }
  }, /*#__PURE__*/React.createElement(Card, {
    padding: 18
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--fw-medium) 14px/1.4 var(--font-sans)'
    }
  }, "\uC815\uB958\uC7A5 \uAE30\uB85D"), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 12
    }
  }, /*#__PURE__*/React.createElement(StopTimeline, {
    dense: true,
    stops: M_STOPS.map(s => ({
      ...s,
      state: 'done'
    }))
  })))), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 18
    }
  }, /*#__PURE__*/React.createElement(Button, {
    size: "lg",
    block: true,
    variant: "secondary"
  }, "\uD559\uC6D0\uC5D0 \uB9AC\uD3EC\uD2B8 \uC804\uC1A1")));
}
Object.assign(window, {
  ManagerLogin,
  ManagerHome,
  DriveMode,
  StopRoster,
  DelayScreen,
  RouteMapScreen,
  RunEndScreen
});
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/manager-app/ManagerScreens.jsx", error: String((e && e.message) || e) }); }

// ui_kits/manager-app/kit.jsx
try { (() => {
const {
  AppHeader,
  TabBar,
  IconButton,
  SegmentedControl
} = window.DesignSystem_9e66e1;
function ManagerApp({
  theme
}) {
  const [signed, setSigned] = React.useState(false);
  const [role, setRole] = React.useState('버스기사');
  const [code, setCode] = React.useState('');
  const [tab, setTab] = React.useState('drive');
  const [page, setPage] = React.useState(null);
  const [sent, setSent] = React.useState(false);
  const tabs = [{
    value: 'home',
    label: '담당 노선',
    icon: 'route'
  }, {
    value: 'drive',
    label: '운행모드',
    icon: 'navigation'
  }, {
    value: 'map',
    label: '지도',
    icon: 'map-pin'
  }, {
    value: 'end',
    label: '리포트',
    icon: 'list'
  }];
  let header, body;
  if (page === 'stop') {
    header = /*#__PURE__*/React.createElement(AppHeader, {
      back: true,
      onBack: () => setPage(null),
      title: "\uC815\uB958\uC7A5 \uD0D1\uC2B9\uC790",
      subtitle: "3-2\uD638\uCC28 \xB7 \uB4F1\uC6D0"
    });
    body = /*#__PURE__*/React.createElement(StopRoster, null);
  } else if (page === 'delay') {
    header = /*#__PURE__*/React.createElement(AppHeader, {
      back: true,
      onBack: () => setPage(null),
      title: "\uC9C0\uC5F0 \uC54C\uB9BC",
      subtitle: "3-2\uD638\uCC28 \xB7 \uB4F1\uC6D0"
    });
    body = /*#__PURE__*/React.createElement(DelayScreen, {
      onSend: () => setPage(null)
    });
  } else if (tab === 'home') {
    header = /*#__PURE__*/React.createElement(AppHeader, {
      title: "\uBC14\uB798\uB2E4 \uB9E4\uB2C8\uC800",
      subtitle: role + ' · 3-2호차',
      actions: /*#__PURE__*/React.createElement(IconButton, {
        icon: "bell",
        label: "\uC54C\uB9BC",
        tone: "inverse"
      })
    });
    body = /*#__PURE__*/React.createElement(ManagerHome, {
      role: role,
      onOpenDrive: () => setTab('drive'),
      onOpenStop: () => setPage('stop')
    });
  } else if (tab === 'drive') {
    header = /*#__PURE__*/React.createElement(AppHeader, {
      title: "\uC6B4\uD589\uBAA8\uB4DC",
      subtitle: "3-2\uD638\uCC28 \xB7 \uB4F1\uC6D0 \xB7 \uC774\uB3D9 \uC911",
      actions: /*#__PURE__*/React.createElement(IconButton, {
        icon: "users-round",
        label: "\uBA85\uB2E8",
        tone: "inverse",
        onClick: () => setPage('stop')
      })
    });
    body = /*#__PURE__*/React.createElement(DriveMode, {
      sent: sent,
      onSendArrive: () => setSent(true),
      onOpenDelay: () => setPage('delay')
    });
  } else if (tab === 'map') {
    header = /*#__PURE__*/React.createElement(AppHeader, {
      title: "\uAE08\uC77C \uB178\uC120 \uC9C0\uB3C4",
      subtitle: "\uCD9C\uBC1C 30\uBD84 \uC804 \uD655\uC815"
    });
    body = /*#__PURE__*/React.createElement(RouteMapScreen, null);
  } else {
    header = /*#__PURE__*/React.createElement(AppHeader, {
      title: "\uC6B4\uD589 \uC885\uB8CC \uB9AC\uD3EC\uD2B8",
      subtitle: "3-2\uD638\uCC28 \xB7 \uB4F1\uC6D0"
    });
    body = /*#__PURE__*/React.createElement(RunEndScreen, null);
  }
  return /*#__PURE__*/React.createElement("div", {
    "data-theme": theme,
    style: {
      width: 390,
      height: 844,
      borderRadius: 28,
      overflow: 'hidden',
      background: 'var(--bg-base)',
      color: 'var(--text-primary)',
      boxShadow: 'var(--shadow-raised)',
      display: 'flex',
      flexDirection: 'column',
      position: 'relative'
    }
  }, !signed ? /*#__PURE__*/React.createElement(ManagerLogin, {
    role: role,
    setRole: setRole,
    code: code,
    setCode: setCode,
    onSubmit: () => setSigned(true)
  }) : /*#__PURE__*/React.createElement(React.Fragment, null, header, /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1,
      minHeight: 0,
      overflowY: 'auto',
      position: 'relative'
    }
  }, body), /*#__PURE__*/React.createElement(TabBar, {
    items: tabs,
    value: tab,
    onChange: v => {
      setTab(v);
      setPage(null);
    }
  })));
}
function ManagerKitRoot() {
  const [theme, setTheme] = React.useState('dark');
  return /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      gap: 28,
      alignItems: 'flex-start'
    }
  }, /*#__PURE__*/React.createElement(ManagerApp, {
    theme: theme
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      maxWidth: 300,
      paddingTop: 8
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--fw-bold) 11px/1 var(--font-sans)',
      letterSpacing: '.16em',
      color: 'var(--text-tertiary)'
    }
  }, "DRIVER \xB7 ONBOARD MANAGER APP"), /*#__PURE__*/React.createElement("h2", {
    style: {
      marginTop: 12,
      fontSize: 26
    }
  }, "\uB9E4\uB2C8\uC800 \uC571"), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 10,
      font: 'var(--fw-light) 14px/1.8 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, "\uAE30\uBCF8\uC740 \uB2E4\uD06C\uC785\uB2C8\uB2E4 \u2014 \uC57C\uAC04 \uD558\uC6D0\uACFC \uC6B4\uC804 \uC911 \uC2DC\uC778\uC131\uC744 \uC704\uD574\uC11C\uC785\uB2C8\uB2E4."), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 16,
      maxWidth: 220
    }
  }, /*#__PURE__*/React.createElement(SegmentedControl, {
    options: [{
      value: 'dark',
      label: '다크'
    }, {
      value: 'light',
      label: '라이트'
    }],
    value: theme,
    onChange: setTheme,
    block: true
  })), /*#__PURE__*/React.createElement("ul", {
    style: {
      marginTop: 18,
      paddingLeft: 18,
      font: 'var(--fw-light) 13px/1.9 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, /*#__PURE__*/React.createElement("li", null, "\uB85C\uADF8\uC778 \u2014 \uBC84\uC2A4\uAE30\uC0AC/\uB3D9\uC2B9\uC790 \uCF54\uB4DC"), /*#__PURE__*/React.createElement("li", null, "\uB2F4\uB2F9 \uB178\uC120 \u2014 \uC815\uB958\uC7A5 \uB9AC\uC2A4\uD2B8, \uD0ED\uD558\uBA74 \uBA85\uB2E8"), /*#__PURE__*/React.createElement("li", null, "\uC6B4\uD589\uBAA8\uB4DC \u2014 \uD604\uC7AC/\uB2E4\uC74C \uC815\uB958\uC7A5, \uB3C4\uCC29 \uC54C\uB9BC \uC804\uC1A1"), /*#__PURE__*/React.createElement("li", null, "\uC815\uB958\uC7A5 \uD0D1\uC2B9\uC790 \u2014 \uD0D1\uC2B9\xB7\uBBF8\uB4F1\uC6D0\xB7\uD558\uCC28, \uC77C\uAD04 \uCC98\uB9AC"), /*#__PURE__*/React.createElement("li", null, "\uC9C0\uC5F0 \uC54C\uB9BC \u2014 \uC0AC\uC720 + 5\uBD84 \uB2E8\uC704, \uBB38\uAD6C \uBBF8\uB9AC\uBCF4\uAE30"), /*#__PURE__*/React.createElement("li", null, "\uC9C0\uB3C4 \u2014 \uAE08\uC77C \uCD94\uAC00(\uCD08\uB85D)\xB7\uC0AD\uC81C(\uBE68\uAC15) \uB178\uC120"), /*#__PURE__*/React.createElement("li", null, "\uB9AC\uD3EC\uD2B8 \u2014 \uC6B4\uD589 \uC885\uB8CC \uC694\uC57D"))));
}
ReactDOM.createRoot(document.getElementById('root')).render(/*#__PURE__*/React.createElement(ManagerKitRoot, null));
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/manager-app/kit.jsx", error: String((e && e.message) || e) }); }

// ui_kits/parent-app/ParentScreens.jsx
try { (() => {
const {
  Button,
  IconButton,
  Card,
  StatusPill,
  Badge,
  Icon,
  Input,
  Select,
  Switch,
  SegmentedControl,
  CodeInput,
  AlertBanner,
  NotificationCard,
  BottomSheet,
  EmptyState,
  AppHeader,
  TabBar,
  StopTimeline,
  RunSummaryCard
} = window.DesignSystem_9e66e1;
const STOPS = [{
  name: '한화아파트',
  address: '대치동 316-1',
  time: '8:37',
  state: 'done',
  riders: 4
}, {
  name: '대치사거리',
  address: '대치동 902',
  time: '8:44',
  state: 'current',
  riders: 3,
  missed: 1
}, {
  name: '은마아파트',
  address: '대치동 316',
  time: '8:51',
  state: 'next',
  riders: 5
}, {
  name: '한빛학원 앞',
  address: '대치동 977',
  time: '8:58',
  state: 'upcoming',
  riders: 0
}];
function LoginScreen({
  role,
  setRole,
  code,
  setCode,
  onSubmit
}) {
  return /*#__PURE__*/React.createElement("div", {
    style: {
      height: '100%',
      background: 'var(--green-600)',
      display: 'flex',
      flexDirection: 'column'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '56px 24px 0'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--fw-bold) 11px/1 var(--font-sans)',
      letterSpacing: '.18em',
      color: 'var(--green-200)'
    }
  }, "BARAEDA"), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 14,
      font: 'var(--fw-black) 34px/1.25 var(--font-serif)',
      letterSpacing: '-.03em',
      color: 'var(--off-white)'
    }
  }, "\uC798 \uD0D4\uACE0,", /*#__PURE__*/React.createElement("br", null), "\uC798 \uB0B4\uB838\uC2B5\uB2C8\uB2E4"), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 12,
      font: 'var(--fw-light) 14px/1.7 var(--font-sans)',
      color: 'var(--green-200)'
    }
  }, "\uBC14\uB798\uB2E4\uC8FC\uC9C0 \uBABB\uD558\uB294 \uB0A0\uC5D0\uB3C4, \uBC14\uB798\uB2E4\uC900 \uAC83\uCC98\uB7FC.")), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 'auto',
      background: 'var(--bg-base)',
      borderRadius: '24px 24px 0 0',
      padding: '26px 20px 28px'
    }
  }, /*#__PURE__*/React.createElement(SegmentedControl, {
    options: ['학부모', '학생'],
    value: role,
    onChange: setRole,
    block: true
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 18
    }
  }, /*#__PURE__*/React.createElement(CodeInput, {
    label: role + ' 코드',
    value: code,
    onChange: setCode,
    hint: "\uD559\uC6D0\uC5D0\uC11C \uBC1B\uC740 6\uC790\uB9AC \uCF54\uB4DC\uB97C \uC785\uB825\uD574 \uC8FC\uC138\uC694"
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 18
    }
  }, /*#__PURE__*/React.createElement(Button, {
    size: "lg",
    block: true,
    onClick: onSubmit,
    disabled: code.length < 6
  }, "\uB85C\uADF8\uC778")), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 14,
      textAlign: 'center',
      font: 'var(--fw-light) 13px/1.5 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, "\uCF54\uB4DC\uB97C \uBAA8\uB974\uC2DC\uBA74 \uD559\uC6D0 \uB370\uC2A4\uD06C\uC5D0 \uBB38\uC758\uD574 \uC8FC\uC138\uC694.")));
}
function HomeScreen({
  role,
  attend,
  setAttend,
  onOpenMap,
  onOpenRoute
}) {
  return /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '18px 20px 0'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--fw-light) 13px/1.4 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, "8\uC6D4 11\uC77C \uD654\uC694\uC77C \xB7 \uB4F1\uC6D0"), /*#__PURE__*/React.createElement("h2", {
    style: {
      marginTop: 4,
      fontSize: 26
    }
  }, role === '학생' ? '오늘도 잘 다녀오세요' : '하준이의 오늘')), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '16px 20px 0'
    }
  }, /*#__PURE__*/React.createElement(AlertBanner, {
    tone: "moving",
    title: "\uC57D 5\uBD84 \uD6C4 \uB3C4\uCC29\uD569\uB2C8\uB2E4"
  }, "\uB3C4\uB85C \uC0C1\uD669\uC73C\uB85C 3-2\uD638\uCC28\uAC00 5\uBD84 \uB2A6\uC5B4\uC9C0\uACE0 \uC788\uC2B5\uB2C8\uB2E4.")), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '12px 20px 0'
    }
  }, /*#__PURE__*/React.createElement(RunSummaryCard, {
    bus: "3-2\uD638\uCC28",
    leg: "\uB4F1\uC6D0",
    status: "moving",
    statusLabel: "\uC774\uB3D9 \uC911 \xB7 \uC9C0\uC5F0",
    eta: "\uC57D 5\uBD84 \uD6C4 \uB3C4\uCC29\uD569\uB2C8\uB2E4",
    currentStop: "\uB300\uCE58\uC0AC\uAC70\uB9AC",
    nextStop: "\uC740\uB9C8\uC544\uD30C\uD2B8",
    driver: "\uBC15\uC815\uD638",
    manager: "\uAE40\uC724\uC815",
    onClick: onOpenMap
  })), role === '학부모' ? /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '12px 20px 0'
    }
  }, /*#__PURE__*/React.createElement(Card, {
    padding: 16
  }, /*#__PURE__*/React.createElement(Select, {
    label: "\uC624\uB298 \uD559\uC6D0 \uB4F1\uC6D0 \uC5EC\uBD80",
    options: ['등원', '미등원'],
    value: attend,
    onChange: e => setAttend(e.target.value)
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 10,
      font: 'var(--fw-light) 13px/1.5 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, "\uBBF8\uB4F1\uC6D0\uC73C\uB85C \uB450\uBA74 \uAE30\uC0AC\uC640 \uB3D9\uC2B9 \uB9E4\uB2C8\uC800\uC5D0\uAC8C \uD568\uAED8 \uC548\uB0B4\uB429\uB2C8\uB2E4."))) : null, /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '20px 20px 0',
      display: 'flex',
      alignItems: 'center'
    }
  }, /*#__PURE__*/React.createElement("h3", {
    style: {
      fontSize: 20
    }
  }, "\uC54C\uB9BC"), /*#__PURE__*/React.createElement(Button, {
    variant: "ghost",
    size: "sm",
    iconEnd: "chevron-right",
    style: {
      marginLeft: 'auto'
    },
    onClick: onOpenRoute
  }, "\uB178\uC120 \uC790\uC138\uD788 \uBCF4\uAE30")), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '10px 20px 24px',
      display: 'flex',
      flexDirection: 'column',
      gap: 10
    }
  }, /*#__PURE__*/React.createElement(NotificationCard, {
    status: "boarded",
    title: "\uD558\uC900\uC774\uAC00 \uC2B9\uCC28\uD588\uC5B4\uC694",
    meta: "8:37 \xB7 \uD55C\uD654\uC544\uD30C\uD2B8 \uC815\uB958\uC7A5",
    sub: "\uB3C4\uCC29 \uC608\uC815 8:58",
    time: "\uBC29\uAE08",
    unread: true
  }), /*#__PURE__*/React.createElement(NotificationCard, {
    status: "moving",
    statusLabel: "\uC774\uB3D9 \uC911 \xB7 \uC9C0\uC5F0",
    title: "\uC57D 5\uBD84 \uD6C4 \uB3C4\uCC29\uD569\uB2C8\uB2E4",
    meta: "\uD604\uC7AC \uC704\uCE58 \xB7 \uB300\uCE58\uC0AC\uAC70\uB9AC",
    time: "2\uBD84 \uC804"
  }), /*#__PURE__*/React.createElement(NotificationCard, {
    status: "boarded",
    statusLabel: "\uD558\uCC28 \uC644\uB8CC",
    title: "\uC5B4\uC81C \uD558\uC6D0, \uC798 \uB0B4\uB838\uC2B5\uB2C8\uB2E4",
    meta: "20:14 \xB7 \uD55C\uD654\uC544\uD30C\uD2B8 \uC815\uB958\uC7A5",
    sub: "\uB3D9\uC2B9 \uB9E4\uB2C8\uC800\uAC00 \uC544\uD30C\uD2B8 \uC815\uBB38\uAE4C\uC9C0 \uD568\uAED8 \uB0B4\uB838\uC2B5\uB2C8\uB2E4",
    time: "\uC5B4\uC81C"
  })));
}
function LiveMapScreen({
  sheetOpen,
  setSheetOpen
}) {
  return /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'relative',
      height: '100%'
    }
  }, /*#__PURE__*/React.createElement(MapSurface, {
    height: 520,
    stops: STOPS,
    busAt: 0.42
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'absolute',
      left: 16,
      right: 16,
      top: 16
    }
  }, /*#__PURE__*/React.createElement(Card, {
    padding: 14,
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 10
    }
  }, /*#__PURE__*/React.createElement(StatusPill, {
    status: "moving"
  }, "\uC774\uB3D9 \uC911"), /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--fw-medium) 14px/1.3 var(--font-sans)'
    }
  }, "\uD604\uC7AC \uC704\uCE58 \xB7 \uB300\uCE58\uC0AC\uAC70\uB9AC"), /*#__PURE__*/React.createElement("span", {
    style: {
      marginLeft: 'auto',
      font: 'var(--fw-bold) 14px/1 var(--font-sans)',
      color: 'var(--status-moving)'
    }
  }, "\uC57D 5\uBD84"))), /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'absolute',
      left: 0,
      right: 0,
      bottom: 0,
      background: 'var(--surface-card)',
      borderRadius: '24px 24px 0 0',
      boxShadow: 'var(--shadow-sheet)',
      padding: '14px 20px 20px'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      width: 40,
      height: 4,
      borderRadius: 999,
      background: 'var(--stone-200)',
      margin: '0 auto 14px'
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 8,
      marginBottom: 12
    }
  }, /*#__PURE__*/React.createElement("h3", {
    style: {
      fontSize: 19
    }
  }, "3-2\uD638\uCC28 \xB7 \uB4F1\uC6D0"), /*#__PURE__*/React.createElement(Badge, {
    tone: "brand",
    style: {
      marginLeft: 'auto'
    }
  }, "\uB3C4\uCC29 \uC608\uC815 8:58")), /*#__PURE__*/React.createElement(StopTimeline, {
    dense: true,
    stops: STOPS,
    onSelect: () => setSheetOpen(true)
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 14,
      display: 'flex',
      gap: 8
    }
  }, /*#__PURE__*/React.createElement(Button, {
    variant: "secondary",
    icon: "phone",
    style: {
      flex: 1
    }
  }, "\uAE30\uC0AC\uC5D0\uAC8C \uC5F0\uB77D"), /*#__PURE__*/React.createElement(Button, {
    style: {
      flex: 1
    },
    onClick: () => setSheetOpen(true)
  }, "\uC815\uB958\uC7A5 \uBCF4\uAE30"))), /*#__PURE__*/React.createElement(BottomSheet, {
    open: sheetOpen,
    title: "\uB300\uCE58\uC0AC\uAC70\uB9AC",
    onClose: () => setSheetOpen(false)
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--fw-light) 13px/1.6 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, "\uB300\uCE58\uB3D9 902 \xB7 \uB3C4\uCC29 \uC608\uC815 8:44"), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 14,
      display: 'flex',
      gap: 10
    }
  }, /*#__PURE__*/React.createElement(Card, {
    tone: "mist",
    padding: 14,
    style: {
      flex: 1
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--fw-medium) 12px/1 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, "\uD0D1\uC2B9 \uC778\uC6D0"), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 6,
      font: 'var(--fw-bold) 22px/1 var(--font-sans)'
    }
  }, "3\uBA85")), /*#__PURE__*/React.createElement(Card, {
    tone: "mist",
    padding: 14,
    style: {
      flex: 1
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--fw-medium) 12px/1 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, "\uBBF8\uD0D1\uC2B9"), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 6,
      font: 'var(--fw-bold) 22px/1 var(--font-sans)',
      color: 'var(--status-missed)'
    }
  }, "1\uBA85"))), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 16
    }
  }, /*#__PURE__*/React.createElement(Button, {
    block: true,
    onClick: () => setSheetOpen(false)
  }, "\uB2EB\uAE30"))));
}
function RouteDetailScreen() {
  return /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement(MapSurface, {
    height: 180,
    stops: STOPS,
    busAt: 0.42
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '18px 20px 0'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 8
    }
  }, /*#__PURE__*/React.createElement("h3", {
    style: {
      fontSize: 21
    }
  }, "3-2\uD638\uCC28 \uACE0\uC815 \uB178\uC120"), /*#__PURE__*/React.createElement(Badge, {
    tone: "brand",
    style: {
      marginLeft: 'auto'
    }
  }, "\uB4F1\uC6D0 4\uAC1C \uC815\uB958\uC7A5")), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 6,
      font: 'var(--fw-light) 13px/1.6 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, "\uAE30\uC0AC \uBC15\uC815\uD638 \xB7 \uB3D9\uC2B9 \uB9E4\uB2C8\uC800 \uAE40\uC724\uC815 \xB7 \uCD9C\uBC1C 8:30")), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '16px 20px 24px'
    }
  }, /*#__PURE__*/React.createElement(Card, {
    padding: 18
  }, /*#__PURE__*/React.createElement(StopTimeline, {
    stops: STOPS
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 12
    }
  }, /*#__PURE__*/React.createElement(Card, {
    tone: "mist",
    padding: 16
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--fw-medium) 14px/1.4 var(--font-sans)'
    }
  }, "\uD558\uC900\uC774\uC758 \uD0D1\uC2B9 \uC815\uB958\uC7A5"), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 4,
      font: 'var(--fw-light) 13px/1.6 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, "\uD55C\uD654\uC544\uD30C\uD2B8 \xB7 \uB4F1\uC6D0 8:37 / \uD558\uC6D0 20:14")))));
}
function ScheduleScreen({
  onDone
}) {
  const [stop, setStop] = React.useState('한화아파트');
  return /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '18px 20px 24px'
    }
  }, /*#__PURE__*/React.createElement("h2", {
    style: {
      fontSize: 24
    }
  }, "\uD0D1\uC2B9 \uC704\uCE58 \uBCC0\uACBD"), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 8,
      font: 'var(--fw-light) 14px/1.7 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, "\uBCC0\uACBD\uD55C \uC704\uCE58\uB294 \uD559\uC6D0 \uD655\uC778 \uD6C4 \uC801\uC6A9\uB429\uB2C8\uB2E4. \uC624\uB298 \uC6B4\uD589\uC5D0\uB294 \uBC18\uC601\uB418\uC9C0 \uC54A\uC2B5\uB2C8\uB2E4."), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 20,
      display: 'flex',
      flexDirection: 'column',
      gap: 14
    }
  }, /*#__PURE__*/React.createElement(Select, {
    label: "\uBCC0\uACBD\uD560 \uC694\uC77C",
    options: ['매일', '월요일', '화요일', '수요일', '목요일', '금요일']
  }), /*#__PURE__*/React.createElement(Select, {
    label: "\uD0D1\uC2B9 \uC815\uB958\uC7A5",
    options: ['한화아파트', '대치사거리', '은마아파트'],
    value: stop,
    onChange: e => setStop(e.target.value)
  }), /*#__PURE__*/React.createElement(Input, {
    label: "\uC0C1\uC138 \uC8FC\uC18C",
    defaultValue: "\uB300\uCE58\uB3D9 316-1 \uD55C\uD654\uC544\uD30C\uD2B8 \uC815\uBB38"
  }), /*#__PURE__*/React.createElement(Input, {
    label: "\uBCC0\uACBD \uC0AC\uC720 (\uC120\uD0DD)",
    placeholder: "\uC608: \uC218\uC694\uC77C\uC740 \uD560\uBA38\uB2C8 \uC9D1\uC5D0\uC11C \uD558\uC6D0\uD569\uB2C8\uB2E4"
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 22
    }
  }, /*#__PURE__*/React.createElement(Button, {
    size: "lg",
    block: true,
    onClick: onDone
  }, "\uBCC0\uACBD \uC2E0\uCCAD")));
}
function SettingsScreen({
  role
}) {
  const [arrive, setArrive] = React.useState(true);
  const [ride, setRide] = React.useState(true);
  const [attend, setAttend] = React.useState(role === '학부모');
  return /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '18px 20px 24px'
    }
  }, /*#__PURE__*/React.createElement("h2", {
    style: {
      fontSize: 24
    }
  }, "\uC54C\uB9BC \uC124\uC815"), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 8,
      font: 'var(--fw-light) 14px/1.7 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, "\uB048 \uC54C\uB9BC\uC740 \uC571 \uC548\uC5D0\uC11C\uB9CC \uD655\uC778\uD560 \uC218 \uC788\uC2B5\uB2C8\uB2E4."), /*#__PURE__*/React.createElement(Card, {
    padding: 4,
    style: {
      marginTop: 18
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '4px 16px'
    }
  }, /*#__PURE__*/React.createElement(Switch, {
    checked: arrive,
    onChange: e => setArrive(e.target.checked),
    label: "\uBC84\uC2A4 \uB3C4\uCC29 \uC54C\uB9BC",
    sublabel: "\uC815\uB958\uC7A5 2\uAC1C \uC804\uC5D0 \uC54C\uB824\uB4DC\uB9BD\uB2C8\uB2E4"
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      height: 1,
      background: 'var(--border-subtle)'
    }
  }), /*#__PURE__*/React.createElement(Switch, {
    checked: ride,
    onChange: e => setRide(e.target.checked),
    label: "\uB4F1\uD558\uC6D0 \uC54C\uB9BC",
    sublabel: "\uC2B9\uCC28\xB7\uD558\uCC28 \uC2DC\uAC01\uC744 \uC54C\uB824\uB4DC\uB9BD\uB2C8\uB2E4"
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      height: 1,
      background: 'var(--border-subtle)'
    }
  }), /*#__PURE__*/React.createElement(Switch, {
    checked: true,
    disabled: true,
    label: "\uB3C4\uCC29 \uC9C0\uC5F0 \uC54C\uB9BC",
    sublabel: "\uC548\uC804\uC744 \uC704\uD574 \uB04C \uC218 \uC5C6\uC2B5\uB2C8\uB2E4"
  }), role === '학부모' ? /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement("div", {
    style: {
      height: 1,
      background: 'var(--border-subtle)'
    }
  }), /*#__PURE__*/React.createElement(Switch, {
    checked: attend,
    onChange: e => setAttend(e.target.checked),
    label: "\uBBF8\uD0D1\uC2B9 \uC54C\uB9BC",
    sublabel: "\uC815\uB958\uC7A5\uC5D0\uC11C \uD0D1\uC2B9\uC774 \uD655\uC778\uB418\uC9C0 \uC54A\uC73C\uBA74 \uBC14\uB85C \uC54C\uB824\uB4DC\uB9BD\uB2C8\uB2E4"
  })) : null)), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 20
    }
  }, /*#__PURE__*/React.createElement("h3", {
    style: {
      fontSize: 18
    }
  }, "\uB0B4 \uC815\uBCF4"), /*#__PURE__*/React.createElement(Card, {
    padding: 16,
    style: {
      marginTop: 10
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 12
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      width: 42,
      height: 42,
      borderRadius: 999,
      background: 'var(--bg-subtle)',
      color: 'var(--text-brand)',
      display: 'grid',
      placeItems: 'center',
      font: 'var(--fw-bold) 15px var(--font-sans)'
    }
  }, "\uD558\uC900"), /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--fw-medium) 15px/1.4 var(--font-sans)'
    }
  }, "\uAE40\uD558\uC900"), /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--fw-light) 13px/1.5 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, "\uD55C\uBE5B\uD559\uC6D0 3\uD559\uB144 2\uBC18 \xB7 3-2\uD638\uCC28")), /*#__PURE__*/React.createElement("span", {
    style: {
      marginLeft: 'auto',
      color: 'var(--text-tertiary)'
    }
  }, /*#__PURE__*/React.createElement(Icon, {
    name: "chevron-right",
    size: 18
  }))))));
}
Object.assign(window, {
  LoginScreen,
  HomeScreen,
  LiveMapScreen,
  RouteDetailScreen,
  ScheduleScreen,
  SettingsScreen,
  PARENT_STOPS: STOPS
});
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/parent-app/ParentScreens.jsx", error: String((e && e.message) || e) }); }

// ui_kits/parent-app/kit.jsx
try { (() => {
const {
  AppHeader,
  TabBar,
  IconButton,
  Icon
} = window.DesignSystem_9e66e1;
function ParentApp() {
  const [signed, setSigned] = React.useState(false);
  const [role, setRole] = React.useState('학부모');
  const [code, setCode] = React.useState('');
  const [tab, setTab] = React.useState('home');
  const [page, setPage] = React.useState(null);
  const [attend, setAttend] = React.useState('등원');
  const [sheet, setSheet] = React.useState(false);
  const tabs = [{
    value: 'home',
    label: '홈',
    icon: 'house'
  }, {
    value: 'map',
    label: '실시간',
    icon: 'map-pin'
  }, {
    value: 'route',
    label: '노선',
    icon: 'route'
  }, {
    value: 'settings',
    label: '설정',
    icon: 'settings'
  }];
  let header,
    body,
    scroll = true;
  if (page === 'schedule') {
    header = /*#__PURE__*/React.createElement(AppHeader, {
      tone: "plain",
      back: true,
      onBack: () => setPage(null),
      title: "\uD0D1\uC2B9 \uC704\uCE58 \uBCC0\uACBD"
    });
    body = /*#__PURE__*/React.createElement(ScheduleScreen, {
      onDone: () => setPage(null)
    });
  } else if (tab === 'home') {
    header = /*#__PURE__*/React.createElement(AppHeader, {
      title: "\uBC14\uB798\uB2E4",
      subtitle: '3-2호차 · 등원 · ' + role,
      actions: /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement(IconButton, {
        icon: "calendar",
        label: "\uD0D1\uC2B9 \uC704\uCE58 \uBCC0\uACBD",
        tone: "inverse",
        onClick: () => setPage('schedule')
      }), /*#__PURE__*/React.createElement(IconButton, {
        icon: "bell",
        label: "\uC54C\uB9BC",
        tone: "inverse"
      }))
    });
    body = /*#__PURE__*/React.createElement(HomeScreen, {
      role: role,
      attend: attend,
      setAttend: setAttend,
      onOpenMap: () => setTab('map'),
      onOpenRoute: () => setTab('route')
    });
  } else if (tab === 'map') {
    header = /*#__PURE__*/React.createElement(AppHeader, {
      title: "\uC2E4\uC2DC\uAC04 \uC6B4\uD589 \uC815\uBCF4",
      subtitle: "3-2\uD638\uCC28 \xB7 \uB4F1\uC6D0"
    });
    body = /*#__PURE__*/React.createElement(LiveMapScreen, {
      sheetOpen: sheet,
      setSheetOpen: setSheet
    });
    scroll = false;
  } else if (tab === 'route') {
    header = /*#__PURE__*/React.createElement(AppHeader, {
      title: "\uC0C1\uC138 \uB178\uC120",
      subtitle: "3-2\uD638\uCC28 \uACE0\uC815 \uB178\uC120"
    });
    body = /*#__PURE__*/React.createElement(RouteDetailScreen, null);
  } else {
    header = /*#__PURE__*/React.createElement(AppHeader, {
      tone: "plain",
      title: "\uC124\uC815"
    });
    body = /*#__PURE__*/React.createElement(SettingsScreen, {
      role: role
    });
  }
  return /*#__PURE__*/React.createElement("div", {
    style: {
      width: 390,
      height: 844,
      borderRadius: 28,
      overflow: 'hidden',
      background: 'var(--bg-base)',
      boxShadow: 'var(--shadow-raised)',
      display: 'flex',
      flexDirection: 'column',
      position: 'relative'
    }
  }, !signed ? /*#__PURE__*/React.createElement(LoginScreen, {
    role: role,
    setRole: setRole,
    code: code,
    setCode: setCode,
    onSubmit: () => setSigned(true)
  }) : /*#__PURE__*/React.createElement(React.Fragment, null, header, /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1,
      minHeight: 0,
      overflowY: scroll ? 'auto' : 'hidden',
      position: 'relative'
    }
  }, body), /*#__PURE__*/React.createElement(TabBar, {
    items: tabs,
    value: tab,
    onChange: v => {
      setTab(v);
      setPage(null);
    }
  })));
}
function KitRoot() {
  return /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      gap: 28,
      alignItems: 'flex-start'
    }
  }, /*#__PURE__*/React.createElement(ParentApp, null), /*#__PURE__*/React.createElement("div", {
    style: {
      maxWidth: 300,
      paddingTop: 8
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      font: 'var(--fw-bold) 11px/1 var(--font-sans)',
      letterSpacing: '.16em',
      color: 'var(--text-tertiary)'
    }
  }, "PARENT \xB7 STUDENT APP"), /*#__PURE__*/React.createElement("h2", {
    style: {
      marginTop: 12,
      fontSize: 26
    }
  }, "\uD559\uBD80\uBAA8\xB7\uD559\uC0DD \uC571"), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 10,
      font: 'var(--fw-light) 14px/1.8 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, "\uCF54\uB4DC\uB85C \uB85C\uADF8\uC778\uD558\uACE0, \uD648 \u2192 \uC2E4\uC2DC\uAC04 \u2192 \uB178\uC120 \u2192 \uC124\uC815\uC744 \uB20C\uB7EC \uC774\uB3D9\uD574 \uBCF4\uC138\uC694. \uD559\uC0DD\uC73C\uB85C \uB85C\uADF8\uC778\uD558\uBA74 \uB4F1\uC6D0 \uC5EC\uBD80 \uBCC0\uACBD\uACFC \uD0D1\uC2B9 \uC704\uCE58 \uBCC0\uACBD\uC774 \uC0AC\uB77C\uC9D1\uB2C8\uB2E4."), /*#__PURE__*/React.createElement("ul", {
    style: {
      marginTop: 16,
      paddingLeft: 18,
      font: 'var(--fw-light) 13px/1.9 var(--font-sans)',
      color: 'var(--text-secondary)'
    }
  }, /*#__PURE__*/React.createElement("li", null, "\uB85C\uADF8\uC778 \u2014 \uD559\uBD80\uBAA8/\uD559\uC0DD \uCF54\uB4DC 6\uC790\uB9AC"), /*#__PURE__*/React.createElement("li", null, "\uD648 \u2014 \uC624\uB298 \uC6B4\uD589 \uC694\uC57D \xB7 \uC54C\uB9BC \uD0C0\uC784\uB77C\uC778 \xB7 \uB4F1\uC6D0 \uC5EC\uBD80"), /*#__PURE__*/React.createElement("li", null, "\uC2E4\uC2DC\uAC04 \u2014 \uC9C0\uB3C4 + \uC815\uB958\uC7A5 \uD0C0\uC784\uB77C\uC778 \uBC14\uD140\uC2DC\uD2B8"), /*#__PURE__*/React.createElement("li", null, "\uB178\uC120 \u2014 3-2\uD638\uCC28 \uACE0\uC815 \uB178\uC120 \uC0C1\uC138"), /*#__PURE__*/React.createElement("li", null, "\uC124\uC815 \u2014 \uC54C\uB9BC on/off (\uC9C0\uC5F0 \uC54C\uB9BC\uC740 \uACE0\uC815)"), /*#__PURE__*/React.createElement("li", null, "\uD5E4\uB354 \uB2EC\uB825 \uC544\uC774\uCF58 \u2014 \uD0D1\uC2B9 \uC704\uCE58 \uBCC0\uACBD"))));
}
ReactDOM.createRoot(document.getElementById('root')).render(/*#__PURE__*/React.createElement(KitRoot, null));
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/parent-app/kit.jsx", error: String((e && e.message) || e) }); }

__ds_ns.Badge = __ds_scope.Badge;

__ds_ns.Button = __ds_scope.Button;

__ds_ns.Card = __ds_scope.Card;

__ds_ns.Icon = __ds_scope.Icon;

__ds_ns.IconButton = __ds_scope.IconButton;

__ds_ns.StatusPill = __ds_scope.StatusPill;

__ds_ns.AlertBanner = __ds_scope.AlertBanner;

__ds_ns.BottomSheet = __ds_scope.BottomSheet;

__ds_ns.Dialog = __ds_scope.Dialog;

__ds_ns.EmptyState = __ds_scope.EmptyState;

__ds_ns.NotificationCard = __ds_scope.NotificationCard;

__ds_ns.Checkbox = __ds_scope.Checkbox;

__ds_ns.CodeInput = __ds_scope.CodeInput;

__ds_ns.Input = __ds_scope.Input;

__ds_ns.SearchField = __ds_scope.SearchField;

__ds_ns.SegmentedControl = __ds_scope.SegmentedControl;

__ds_ns.Select = __ds_scope.Select;

__ds_ns.Switch = __ds_scope.Switch;

__ds_ns.Textarea = __ds_scope.Textarea;

__ds_ns.AppHeader = __ds_scope.AppHeader;

__ds_ns.PageHeader = __ds_scope.PageHeader;

__ds_ns.SideNav = __ds_scope.SideNav;

__ds_ns.TabBar = __ds_scope.TabBar;

__ds_ns.DelayPicker = __ds_scope.DelayPicker;

__ds_ns.RosterTable = __ds_scope.RosterTable;

__ds_ns.RunSummaryCard = __ds_scope.RunSummaryCard;

__ds_ns.StatCard = __ds_scope.StatCard;

__ds_ns.StopTimeline = __ds_scope.StopTimeline;

__ds_ns.StudentRow = __ds_scope.StudentRow;

})();
