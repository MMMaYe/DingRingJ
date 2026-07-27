import { NavLink } from 'react-router-dom';
import Logo from './Logo';

/* ---- Inline SVG Icons (from design spec) ---- */
const IconChat = () => (
  <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><path d="M13.5 10.5a1.5 1.5 0 01-1.5 1.5H5L2.5 14V4a1.5 1.5 0 011.5-1.5h7a1.5 1.5 0 011.5 1.5v6.5z" stroke="currentColor" strokeWidth="1.2" strokeLinejoin="round"/></svg>
);
const IconFileText = () => (
  <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><path d="M9 1.5H4.5A1.5 1.5 0 003 3v10a1.5 1.5 0 001.5 1.5h7A1.5 1.5 0 0013 13V5L9 1.5z" stroke="currentColor" strokeWidth="1.2" strokeLinejoin="round"/><path d="M9 1.5v3.5H13" stroke="currentColor" strokeWidth="1.2" strokeLinejoin="round"/><path d="M6 8h4M6 10.5h2" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round"/></svg>
);
const IconLayers = () => (
  <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><path d="M8 1.5L1.5 5 8 8.5 14.5 5 8 1.5z" stroke="currentColor" strokeWidth="1.2" strokeLinejoin="round"/><path d="M1.5 8l6.5 3.5L14.5 8" stroke="currentColor" strokeWidth="1.2" strokeLinejoin="round"/><path d="M1.5 11l6.5 3.5L14.5 11" stroke="currentColor" strokeWidth="1.2" strokeLinejoin="round"/></svg>
);
const IconSparkles = () => (
  <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><path d="M8 1.5l1.5 4.5L14 7.5 9.5 9 8 13.5 6.5 9 2 7.5l4.5-1.5L8 1.5z" stroke="currentColor" strokeWidth="1.2" strokeLinejoin="round"/></svg>
);
const IconSearch = () => (
  <svg width="14" height="14" viewBox="0 0 14 14" fill="none"><circle cx="6" cy="6" r="4" stroke="currentColor" strokeWidth="1.2"/><path d="M9.5 9.5L12.5 12.5" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round"/></svg>
);
const IconPlus = () => (
  <svg width="14" height="14" viewBox="0 0 14 14" fill="none"><path d="M7 2.5v9M2.5 7h9" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round"/></svg>
);

interface SidebarProps {
  children?: React.ReactNode;
  footer?: React.ReactNode;
  showGroupLabel?: boolean;
  /** 群列表搜索回调 */
  onSearch?: (keyword: string) => void;
  searchPlaceholder?: string;
  /** 知识卡片角标数量 */
  cardBadge?: number;
}

export default function Sidebar({ children, footer, showGroupLabel, onSearch, searchPlaceholder = '搜索群组...', cardBadge }: SidebarProps) {
  return (
    <aside className="sidebar">
      <div className="sidebar__header">
        <Logo size={28} animate />
        <div className="sidebar__brand">
          <span className="sidebar__title">Ding<span className="sidebar__title-accent">Ring</span></span>
          <span className="sidebar__tagline">学习讨论 · 叮一声</span>
        </div>
      </div>

      {onSearch && (
        <div className="sidebar__search">
          <div className="sidebar__search-wrap">
            <span className="sidebar__search-icon"><IconSearch /></span>
            <input
              type="text"
              className="sidebar__search-input"
              placeholder={searchPlaceholder}
              onChange={e => onSearch(e.target.value)}
            />
          </div>
        </div>
      )}

      <nav className="sidebar__nav">
        <NavLink to="/" end className={({ isActive }) => `sidebar__nav-item${isActive ? ' sidebar__nav-item--active' : ''}`}>
          <span className="sidebar__nav-icon"><IconChat /></span>
          <span className="sidebar__nav-text">群聊</span>
        </NavLink>
        <NavLink to="/cards" className={({ isActive }) => `sidebar__nav-item${isActive ? ' sidebar__nav-item--active' : ''}`}>
          <span className="sidebar__nav-icon"><IconFileText /></span>
          <span className="sidebar__nav-text">知识卡片管理</span>
          {cardBadge != null && cardBadge > 0 && <span className="sidebar__nav-badge">{cardBadge}</span>}
        </NavLink>
        <NavLink to="/kb" className={({ isActive }) => `sidebar__nav-item${isActive ? ' sidebar__nav-item--active' : ''}`}>
          <span className="sidebar__nav-icon"><IconLayers /></span>
          <span className="sidebar__nav-text">知识库管理</span>
        </NavLink>
        <NavLink to="/agents" className={({ isActive }) => `sidebar__nav-item${isActive ? ' sidebar__nav-item--active' : ''}`}>
          <span className="sidebar__nav-icon"><IconSparkles /></span>
          <span className="sidebar__nav-text">Agent 管理</span>
        </NavLink>
      </nav>

      {showGroupLabel && <div className="sidebar__label">我的群组</div>}
      {children}
      {footer && <div className="sidebar__footer">{footer}</div>}
    </aside>
  );
}

export { IconPlus };
