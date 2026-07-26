import { NavLink } from 'react-router-dom';

interface SidebarProps {
  /** 自定义 children 放在导航下方（群列表 / 分类筛选等） */
  children?: React.ReactNode;
  /** 底部按钮 */
  footer?: React.ReactNode;
  /** 群列表模式：展示「我的群组」标签 */
  showGroupLabel?: boolean;
}

export default function Sidebar({ children, footer, showGroupLabel }: SidebarProps) {
  return (
    <aside className="sidebar">
      <div className="sidebar__header">
        <div className="sidebar__logo">D</div>
        <div className="sidebar__title">Ding<em>Ring</em></div>
      </div>
      <nav className="sidebar__nav">
        <NavLink to="/" end className={({ isActive }) => `sidebar__nav-item${isActive ? ' sidebar__nav-item--active' : ''}`}>
          💬 <span>群聊</span>
        </NavLink>
        <NavLink to="/cards" className={({ isActive }) => `sidebar__nav-item${isActive ? ' sidebar__nav-item--active' : ''}`}>
          🗂️ <span>知识卡片</span>
        </NavLink>
        <NavLink to="/agents" className={({ isActive }) => `sidebar__nav-item${isActive ? ' sidebar__nav-item--active' : ''}`}>
          🤖 <span>Agent 管理</span>
        </NavLink>
      </nav>
      {showGroupLabel && <div className="sidebar__label">我的群组</div>}
      {children}
      {footer && <div className="sidebar__footer">{footer}</div>}
    </aside>
  );
}
