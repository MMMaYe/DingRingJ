import { useEffect, useState, useCallback } from 'react';

interface ToastItem {
  id: number;
  message: string;
  type: 'info' | 'success' | 'error';
}

let nextId = 0;
let listeners: Array<(t: ToastItem) => void> = [];

/** 任何模块都可以调用的全局 toast 函数 */
export function toast(message: string, type: ToastItem['type'] = 'info') {
  const item: ToastItem = { id: nextId++, message, type };
  listeners.forEach(fn => fn(item));
}

/** 挂在 App 顶层的 Toast 容器 */
export function ToastContainer() {
  const [items, setItems] = useState<ToastItem[]>([]);

  const add = useCallback((t: ToastItem) => {
    setItems(prev => [...prev, t]);
    setTimeout(() => setItems(prev => prev.filter(x => x.id !== t.id)), 2600);
  }, []);

  useEffect(() => {
    listeners.push(add);
    return () => { listeners = listeners.filter(fn => fn !== add); };
  }, [add]);

  if (!items.length) return null;
  return (
    <div className="toast-wrap">
      {items.map(t => (
        <div key={t.id} className={`toast${t.type === 'error' ? ' toast--error' : t.type === 'success' ? ' toast--success' : ''}`}>
          {t.message}
        </div>
      ))}
    </div>
  );
}
