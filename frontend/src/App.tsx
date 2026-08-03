import { lazy, Suspense } from 'react';
import { Routes, Route, Navigate, useLocation } from 'react-router-dom';
import { ToastContainer } from './components/Toast';
import { WebSocketProvider } from './context/WebSocketContext';
import ChatPage from './pages/Chat';

// 非首屏页面懒加载：减小首屏包体，首次进入群聊更快
const AgentsPage = lazy(() => import('./pages/Agents'));
const CardsPage = lazy(() => import('./pages/Cards'));
const KBPage = lazy(() => import('./pages/KB'));
const TopicsPage = lazy(() => import('./pages/Topics'));

/**
 * 页面切换过渡容器
 *
 * 设计概念：Editorial Reveal —— 杂志翻页般的优雅揭示
 * - 用 useLocation().pathname 作 key 触发主内容区 remount
 * - 配合 CSS staggered animation：容器淡入 + 子元素级联揭示
 * - 缓动 cubic-bezier(.16, 1, .3, 1) 与现有 editorial 设计语言对齐
 *
 * 注：ChatPage 切换回 / 时 WebSocket 会重连，这是路由切换的固有行为
 * （与非过渡状态一致），过渡动画不会引入额外副作用
 */
function PageTransition() {
  const location = useLocation();
  return (
    <div className="page-transition" key={location.pathname}>
      <Suspense fallback={<div className="route-loading"><span className="route-loading__spinner" /></div>}>
        <Routes location={location}>
          <Route path="/" element={<ChatPage />} />
          <Route path="/topics" element={<TopicsPage />} />
          <Route path="/agents" element={<AgentsPage />} />
          <Route path="/cards" element={<CardsPage />} />
          <Route path="/kb" element={<KBPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </Suspense>
    </div>
  );
}

export default function App() {
  return (
    <WebSocketProvider>
      <PageTransition />
      <ToastContainer />
    </WebSocketProvider>
  );
}
