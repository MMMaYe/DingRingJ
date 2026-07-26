const COLORS = ['#2DD288', '#7BB8FF', '#EC93FF', '#DCB364', '#04CBE5', '#BFA5FF', '#FF9392', '#8ACB3A'];

function colorOf(name: string): string {
  let h = 0;
  for (const ch of name || '?') h = (h * 31 + ch.codePointAt(0)!) >>> 0;
  return COLORS[h % COLORS.length];
}

interface AvatarProps {
  name: string;
  size?: 'sm' | 'md' | 'lg';
}

export default function Avatar({ name, size = 'md' }: AvatarProps) {
  const ch = (name || '?').trim().charAt(0) || '?';
  const color = colorOf(name);
  const cls = size === 'sm' ? 'ds-avatar ds-avatar--sm' : size === 'lg' ? 'ds-avatar ds-avatar--lg' : 'ds-avatar';
  return (
    <div className={cls} style={{ background: color + '22', color }}>
      {ch}
    </div>
  );
}
