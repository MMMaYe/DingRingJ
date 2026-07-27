import React, { useEffect } from 'react';

interface ModalProps {
  open: boolean;
  onClose: () => void;
  title: string;
  /** 可选副标题，渲染在主标题下方 */
  subtitle?: string;
  /** 可选眉标（eyebrow），渲染在主标题上方的小号标签 */
  eyebrow?: string;
  width?: number;
  children: React.ReactNode;
  footer?: React.ReactNode;
}

/**
 * 通用 Modal — Editorial 风格标题区。
 *
 * - eyebrow：小号大写字符，作为「栏目」标识
 * - title：衬线大号字（仅 modal 内主标题，不影响全局字体）
 * - subtitle：辅助说明
 * - 标题下方装饰性细线，营造目录感
 */
export default function Modal({ open, onClose, title, subtitle, eyebrow, width, children, footer }: ModalProps) {
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  if (!open) return null;
  return (
    <div className="modal-mask" onClick={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="modal modal--editorial" style={width ? { width } : undefined} role="dialog" aria-modal="true" aria-label={title}>
        <header className="modal__head">
          {eyebrow && <span className="modal__eyebrow">{eyebrow}</span>}
          <h2 className="modal__title">{title}</h2>
          {subtitle && <span className="modal__subtitle">{subtitle}</span>}
          <span className="modal__head-rule" aria-hidden />
        </header>
        <div className="modal__body">
          {children}
        </div>
        {footer && <div className="modal__footer">{footer}</div>}
      </div>
    </div>
  );
}
