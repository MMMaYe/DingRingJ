import useTheme from '../hooks/useTheme';

/**
 * DingRing Logo — 「叮环」（兼主题切换按钮）
 *
 * 设计语义：
 * - 外环（完整圆）：群组圈层，呼应 Ring
 * - 内弧（3/4 旋弧）：讨论流转，与外环形成动态张力
 * - 中心点：随主题切换形态
 *   · dark 模式：弯月（夜间意象）
 *   · light 模式：太阳带光芒（白昼意象）
 * - 点击 Logo 切换主题，整个 SVG 平滑旋转 360°
 *
 * 三层结构在小尺寸（24px）也能辨识，且具备独特性。
 * 主题切换功能集成到 Logo 本身，避免独立按钮破坏品牌区视觉。
 */
interface LogoProps {
  size?: number;
  /** 是否启用中心点脉冲动画（主品牌场景开启） */
  animate?: boolean;
  className?: string;
}

export default function Logo({ size = 28, animate = false, className }: LogoProps) {
  const { theme, toggle } = useTheme();
  const isLight = theme === 'light';

  return (
    <button
      type="button"
      className={`logo-btn${className ? ` ${className}` : ''}`}
      onClick={toggle}
      title={isLight ? '切换到夜间模式' : '切换到白天模式'}
      aria-label={isLight ? '切换到夜间模式' : '切换到白天模式'}
      aria-pressed={isLight}
    >
      <svg
        width={size}
        height={size}
        viewBox="0 0 32 32"
        fill="none"
        className="logo-btn__svg"
        aria-hidden="true"
      >
        {/* 外环：群组圈层（hover 时缓慢顺时针旋转） */}
        <circle
          cx="16"
          cy="16"
          r="13"
          stroke="currentColor"
          strokeWidth="1.4"
          opacity="0.35"
          className="logo-btn__ring"
        />
        {/* 内弧：讨论流转（hover 时反向旋转，与外环形成张力） */}
        <circle
          cx="16"
          cy="16"
          r="9"
          stroke="currentColor"
          strokeWidth="2.2"
          strokeLinecap="round"
          strokeDasharray="42.4 56.5"
          strokeDashoffset={-14}
          className="logo-btn__arc"
        />

        {/* 中心：日月切换 */}
        {isLight ? (
          /* 白天模式：太阳（实心圆 + 8 条短光芒） */
          <g className="logo-btn__sun">
            <circle cx="16" cy="16" r="3.2" fill="currentColor" />
            <g stroke="currentColor" strokeWidth="1.2" strokeLinecap="round">
              <path d="M16 5.5v2.2" />
              <path d="M16 24.3v2.2" />
              <path d="M5.5 16h2.2" />
              <path d="M24.3 16h2.2" />
              <path d="M8.6 8.6l1.5 1.5" />
              <path d="M21.9 21.9l1.5 1.5" />
              <path d="M8.6 23.4l1.5-1.5" />
              <path d="M21.9 10.1l1.5-1.5" />
            </g>
          </g>
        ) : (
          /* 夜间模式：弯月（用一个圆 + 偏移的 mask 切出月牙） */
          <g className="logo-btn__moon">
            <defs>
              <mask id="logo-moon-mask">
                <rect width="32" height="32" fill="white" />
                <circle cx="18" cy="14" r="3.5" fill="black" />
              </mask>
            </defs>
            <circle
              cx="16"
              cy="16"
              r="3.5"
              fill="currentColor"
              mask="url(#logo-moon-mask)"
            />
          </g>
        )}

        {/* 中心脉冲动画（仅主品牌场景 + 夜间模式） */}
        {animate && !isLight && (
          <circle
            cx="16"
            cy="16"
            r="3"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.4"
            className="logo-ripple"
          />
        )}
      </svg>
    </button>
  );
}
