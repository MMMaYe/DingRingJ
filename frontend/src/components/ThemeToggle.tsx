import useTheme from '../hooks/useTheme';

/* 太阳图标（light 模式下显示，点击切回 dark） */
const IconSun = () => (
  <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
    <circle cx="8" cy="8" r="3" stroke="currentColor" strokeWidth="1.2" />
    <path d="M8 1.5v2M8 12.5v2M1.5 8h2M12.5 8h2M3.4 3.4l1.4 1.4M11.2 11.2l1.4 1.4M3.4 12.6l1.4-1.4M11.2 4.8l1.4-1.4"
      stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" />
  </svg>
);

/* 月亮图标（dark 模式下显示，点击切到 light） */
const IconMoon = () => (
  <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
    <path d="M13 9.5A5.5 5.5 0 016.5 3a5.5 5.5 0 100 10 5.5 5.5 0 006.5-3.5z"
      stroke="currentColor" strokeWidth="1.2" strokeLinejoin="round" />
  </svg>
);

/**
 * 主题切换按钮。
 * dark 模式显示月亮（点击切到白天），light 模式显示太阳（点击切回夜间）。
 */
export default function ThemeToggle() {
  const { theme, toggle } = useTheme();
  const isLight = theme === 'light';
  return (
    <button
      type="button"
      className="theme-toggle"
      onClick={toggle}
      title={isLight ? '切换到夜间模式' : '切换到白天模式'}
      aria-label={isLight ? '切换到夜间模式' : '切换到白天模式'}
    >
      <span className="theme-toggle__icon">{isLight ? <IconSun /> : <IconMoon />}</span>
      <span className="theme-toggle__text">{isLight ? '白天模式' : '夜间模式'}</span>
    </button>
  );
}
