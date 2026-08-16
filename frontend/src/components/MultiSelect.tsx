import { useEffect, useRef, useState } from 'react';
import './MultiSelect.css';

export interface MultiSelectOption {
  value: number;
  label: string;
  /** 次要描述（如文件数），右侧弱化展示 */
  desc?: string;
}

interface MultiSelectProps {
  options: MultiSelectOption[];
  selected: number[];
  onChange: (next: number[]) => void;
  placeholder?: string;
  emptyText?: string;
}

/**
 * 通用下拉多选组件。
 *
 * 为什么不用原生 select multiple：原生多选在不同操作系统/浏览器的交互形态差异大
 * （Mac 上呈列表框而非下拉），与项目 Editorial 风格不符；自绘下拉可完全复用
 * 既有 CSS 变量与选中态视觉语言。
 *
 * 交互：点击触发器展开/收起；点击选项即时切换（不收起，便于连续多选）；
 * 点击组件外部或 Esc 关闭。
 */
export default function MultiSelect({
  options, selected, onChange,
  placeholder = '请选择（可多选）', emptyText = '暂无可选项',
}: MultiSelectProps) {
  const [open, setOpen] = useState(false);
  // 外点关闭需要稳定引用 DOM 根，避免 re-render 后 ref 指向旧节点
  const rootRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDocMouseDown = (e: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false); };
    document.addEventListener('mousedown', onDocMouseDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDocMouseDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const toggle = (v: number) => {
    onChange(selected.includes(v) ? selected.filter(x => x !== v) : [...selected, v]);
  };

  const selectedLabels = options.filter(o => selected.includes(o.value)).map(o => o.label);

  return (
    <div className={`mselect${open ? ' is-open' : ''}`} ref={rootRef}>
      <button type="button" className="mselect__trigger" onClick={() => setOpen(o => !o)}
              aria-haspopup="listbox" aria-expanded={open}>
        {selectedLabels.length
          ? <span className="mselect__value">{selectedLabels.join('、')}</span>
          : <span className="mselect__placeholder">{placeholder}</span>}
        <svg className="mselect__chevron" width="12" height="12" viewBox="0 0 12 12" fill="none" aria-hidden>
          <path d="M3 4.5L6 7.5L9 4.5" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round"/>
        </svg>
      </button>
      {open && (
        <div className="mselect__menu" role="listbox" aria-multiselectable>
          {options.length === 0
            ? <div className="mselect__empty">{emptyText}</div>
            : options.map(o => {
              const checked = selected.includes(o.value);
              return (
                <div
                  key={o.value}
                  className={`mselect__option${checked ? ' is-checked' : ''}`}
                  role="option" aria-selected={checked} tabIndex={0}
                  onClick={() => toggle(o.value)}
                  onKeyDown={e => { if (e.key === ' ' || e.key === 'Enter') { e.preventDefault(); toggle(o.value); } }}
                >
                  <span className={`mselect__checkbox${checked ? ' is-checked' : ''}`} aria-hidden>
                    {checked && (
                      <svg width="10" height="10" viewBox="0 0 12 12" fill="none">
                        <path d="M2.5 6.5l2.5 2.5 4.5-5" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"/>
                      </svg>
                    )}
                  </span>
                  <span className="mselect__option-label">{o.label}</span>
                  {o.desc && <span className="mselect__option-desc">{o.desc}</span>}
                </div>
              );
            })}
        </div>
      )}
    </div>
  );
}
