import { useEffect, useMemo, useRef, type KeyboardEvent as ReactKeyboardEvent } from 'react';
import { createPortal } from 'react-dom';
import DOMPurify from 'dompurify';

interface SvgLightboxProps {
  svgMarkup: string;
  restoreFocus: SVGSVGElement | null;
  onClose: () => void;
}

function sanitizePreview(markup: string): string {
  const safe = DOMPurify.sanitize(markup, {
    USE_PROFILES: { html: true, svg: true },
  });
  // The enlarged copy is presentation-only; keep only the close button in the tab order.
  return safe.replace(
    /\s(?:data-chat-svg|role|tabindex|aria-label|focusable)(?:\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+))?/gi,
    '',
  );
}

export default function SvgLightbox({ svgMarkup, restoreFocus, onClose }: SvgLightboxProps) {
  const closeRef = useRef<HTMLButtonElement>(null);
  const safeSvg = useMemo(() => sanitizePreview(svgMarkup), [svgMarkup]);

  useEffect(() => {
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    closeRef.current?.focus({ preventScroll: true });

    return () => {
      document.body.style.overflow = previousOverflow;
      if (restoreFocus?.isConnected) restoreFocus.focus({ preventScroll: true });
    };
  }, [restoreFocus]);

  const handleKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      onClose();
      return;
    }
    if (event.key === 'Tab') {
      // The graphic is presentation-only, so keep keyboard focus on the close control.
      event.preventDefault();
      closeRef.current?.focus({ preventScroll: true });
    }
  };

  return createPortal(
    <div
      className="svg-lightbox"
      role="dialog"
      aria-modal="true"
      aria-labelledby="svg-lightbox-title"
      onClick={event => { if (event.target === event.currentTarget) onClose(); }}
      onKeyDown={handleKeyDown}
    >
      <div className="svg-lightbox__surface">
        <header className="svg-lightbox__header">
          <h2 id="svg-lightbox-title" className="svg-lightbox__title">图形预览</h2>
          <button
            ref={closeRef}
            type="button"
            className="svg-lightbox__close"
            aria-label="关闭图形预览"
            onClick={onClose}
          >
            <span aria-hidden="true">×</span>
          </button>
        </header>
        <div className="svg-lightbox__viewport">
          <div
            className="svg-lightbox__graphic"
            aria-hidden="true"
            dangerouslySetInnerHTML={{ __html: safeSvg }}
          />
        </div>
      </div>
    </div>,
    document.body,
  );
}
