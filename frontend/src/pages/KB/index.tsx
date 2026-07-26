import Sidebar from '../../components/Sidebar';
import './style.css';

export default function KBPage() {
  return (
    <div className="app-shell">
      <Sidebar>
        <div className="sidebar__label">知识库</div>
      </Sidebar>
      <main className="kb-main">
        <div className="kb-header">
          <h1 className="kb-header__title">知识库管理</h1>
          <p className="kb-header__desc">管理向量知识库，查看同步状态和关联群组</p>
        </div>
        <div className="kb-empty">
          <div className="kb-empty__icon">📚</div>
          <p>暂无知识库数据</p>
          <p className="kb-empty__hint">知识库会在讨论生成知识卡片后自动创建</p>
        </div>
      </main>
    </div>
  );
}
