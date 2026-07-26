import { Routes, Route, Navigate } from 'react-router-dom';
import { ToastContainer } from './components/Toast';
import ChatPage from './pages/Chat';
import AgentsPage from './pages/Agents';
import CardsPage from './pages/Cards';

export default function App() {
  return (
    <>
      <Routes>
        <Route path="/" element={<ChatPage />} />
        <Route path="/agents" element={<AgentsPage />} />
        <Route path="/cards" element={<CardsPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
      <ToastContainer />
    </>
  );
}
