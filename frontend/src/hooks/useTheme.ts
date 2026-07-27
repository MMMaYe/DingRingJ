import { useState, useCallback, useEffect } from 'react';

/** 主题类型：dark（默认）/ light（白天模式） */
export type Theme = 'dark' | 'light';

const STORAGE_KEY = 'dingring-theme';

/** 读取 localStorage 中保存的主题，默认 dark */
function readStoredTheme(): Theme {
  try {
    const v = localStorage.getItem(STORAGE_KEY);
    return v === 'light' ? 'light' : 'dark';
  } catch {
    return 'dark';
  }
}

/**
 * 主题切换 Hook。
 * - 在 <html> 上设置 data-theme 属性，配合 tokens.css 中的 [data-theme="light"] 生效
 * - 主题持久化到 localStorage
 * - 注意：为避免首屏闪烁，index.html 中有内联脚本在 React 渲染前完成初次设置
 */
export default function useTheme() {
  const [theme, setTheme] = useState<Theme>(readStoredTheme);

  /** 同步 data-theme 到 <html> 标签 */
  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme);
  }, [theme]);

  /** 持久化到 localStorage */
  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_KEY, theme);
    } catch {
      /* 忽略隐私模式等写入失败 */
    }
  }, [theme]);

  /** 切换 dark <-> light */
  const toggle = useCallback(() => {
    setTheme(prev => (prev === 'dark' ? 'light' : 'dark'));
  }, []);

  return { theme, toggle };
}
