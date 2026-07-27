/**
 * DingRing Logo — 「叮环」
 *
 * 设计语义：
 * - 外环（完整圆）：群组圈层，呼应 Ring
 * - 内弧（3/4 旋弧）：讨论流转，与外环形成动态张力
 * - 中心点（实心圆 + 脉冲）：Ding 声音的爆发点，呼应 Ding
 *
 * 三层结构在小尺寸（24px）也能辨识，且具备独特性：
 * 不像通用闪电/铃铛，是 DingRing 专属符号。
 *
 * @param size - 像素尺寸，默认 28
 * @param animate - 是否启用中心点脉冲动画（仅主品牌场景开启）
 */
interface LogoProps {
  size?: number;
  animate?: boolean;
  className?: string;
}

export default function Logo({ size = 28, animate = false, className }: LogoProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 32 32"
      fill="none"
      className={className}
      aria-hidden="true"
    >
      {/* 外环：群组圈层 */}
      <circle
        cx="16"
        cy="16"
        r="13"
        stroke="currentColor"
        strokeWidth="1.4"
        opacity="0.35"
      />
      {/* 内弧：讨论流转（3/4 弧段，缺口在右上） */}
      <circle
        cx="16"
        cy="16"
        r="9"
        stroke="currentColor"
        strokeWidth="2.2"
        strokeLinecap="round"
        strokeDasharray="42.4 56.5"
        strokeDashoffset={-14}
      />
      {/* 中心叮点：实心圆 */}
      <circle
        cx="16"
        cy="16"
        r="3"
        fill="currentColor"
        className={animate ? 'logo-pulse-dot' : undefined}
      />
      {/* 叮声涟漪：仅动画态显示 */}
      {animate && (
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
  );
}
