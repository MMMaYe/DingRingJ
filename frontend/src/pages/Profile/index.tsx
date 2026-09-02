import { useCallback, useEffect, useMemo, useState } from 'react';
import Sidebar from '../../components/Sidebar';
import { toast } from '../../components/Toast';
import { QuestionnaireApi } from '../../api';
import type { QuestionDef } from '../../api';
import './style.css';

/** 分步向导：每步覆盖的维度（共 4 步走完 6 维度） */
const STEPS: Array<{ title: string; dimensions: string[] }> = [
  { title: '背景与目标', dimensions: ['BACKGROUND', 'GOAL'] },
  { title: '关注领域与水平', dimensions: ['FOCUS'] },
  { title: '讨论风格', dimensions: ['STYLE'] },
  { title: '呈现偏好', dimensions: ['PRESENTATION'] },
];

/** 领域被勾选但未自评时的默认水平 */
const DEFAULT_LEVEL = 'BEGINNER';

/** focusAreas 题目 key（选择变化需联动维护 areaLevels） */
const KEY_FOCUS_AREAS = 'focusAreas';

type Answers = Record<string, unknown>;

interface QuestionBlockProps {
  question: QuestionDef;
  questions: QuestionDef[];
  answers: Answers;
  setValue: (key: string, value: unknown) => void;
  toggleMulti: (key: string, value: string) => void;
}

/** 单题渲染：按 type 分发为选项 chips / 文本输入 / 领域水平联动行 */
function QuestionBlock({ question: q, questions, answers, setValue, toggleMulti }: QuestionBlockProps) {
  const focusQuestion = questions.find(x => x.key === KEY_FOCUS_AREAS);
  const areaLabel = (v: string) =>
    focusQuestion?.options.find(o => o.value === v)?.label ?? v;
  const multiValue = (answers[q.key] as string[] | undefined) ?? [];
  const levels = (answers[q.key] as Record<string, string> | undefined) ?? {};

  return (
    <div className="q-item">
      <div className="q-item__label">
        {q.label}
        {!q.required && <span className="q-item__optional">（可选）</span>}
      </div>

      {q.type === 'SINGLE' && (
        <div className="q-chips">
          {q.options.map(o => (
            <button key={o.value} type="button"
                    className={`q-chip${answers[q.key] === o.value ? ' is-selected' : ''}`}
                    onClick={() => setValue(q.key, o.value)}>
              {o.label}
            </button>
          ))}
        </div>
      )}

      {q.type === 'MULTI' && (
        <div className="q-chips">
          {q.options.map(o => (
            <button key={o.value} type="button"
                    className={`q-chip${multiValue.includes(o.value) ? ' is-selected' : ''}`}
                    onClick={() => toggleMulti(q.key, o.value)}>
              {o.label}
            </button>
          ))}
        </div>
      )}

      {q.type === 'TEXT' && (
        <input
          className="q-input"
          type="text"
          value={(answers[q.key] as string | undefined) ?? ''}
          placeholder={q.key === 'techStack'
            ? '如：Java / Spring Boot / MySQL'
            : q.key === 'goalDescription'
              ? '如：三个月搞定 JVM 调优'
              : '如：Netty 源码'}
          onChange={e => setValue(q.key, e.target.value)}
        />
      )}

      {q.type === 'AREA_LEVELS' && (
        <div className="q-levels">
          {multiValueOfFocus(answers).map(area => (
            <div key={area} className="q-level-row">
              <span className="q-level-name">{areaLabel(area)}</span>
              <div className="q-chips">
                {q.options.map(o => (
                  <button key={o.value} type="button"
                          className={`q-chip q-chip--sm${levels[area] === o.value ? ' is-selected' : ''}`}
                          onClick={() => setValue(q.key, { ...levels, [area]: o.value })}>
                    {o.label}
                  </button>
                ))}
              </div>
            </div>
          ))}
          {!multiValueOfFocus(answers).length && (
            <div className="q-level-empty">先在上一题选择关注领域</div>
          )}
        </div>
      )}
    </div>
  );
}

function multiValueOfFocus(answers: Answers): string[] {
  return (answers[KEY_FOCUS_AREAS] as string[] | undefined) ?? [];
}

export default function ProfilePage() {
  const [questions, setQuestions] = useState<QuestionDef[]>([]);
  const [answers, setAnswers] = useState<Answers>({});
  const [step, setStep] = useState(0);
  const [loaded, setLoaded] = useState(false);
  const [saving, setSaving] = useState(false);

  // 加载 schema + 已存答案；未填过的必答题取首项为默认值
  useEffect(() => {
    (async () => {
      try {
        const dto = await QuestionnaireApi.get();
        setQuestions(dto.questions);
        const init: Answers = {};
        for (const q of dto.questions) {
          if (dto.answers[q.key] !== undefined) {
            init[q.key] = dto.answers[q.key];
          } else if (q.type === 'SINGLE' && q.required && q.options.length > 0) {
            init[q.key] = q.options[0].value;
          } else if (q.type === 'MULTI' && q.required && q.options.length > 0) {
            init[q.key] = [q.options[0].value];
          } else if (q.type === 'AREA_LEVELS') {
            init[q.key] = {};
          }
        }
        setAnswers(init);
      } catch (e: any) {
        toast(e.message || '加载问卷失败', 'error');
      } finally {
        setLoaded(true);
      }
    })();
  }, []);

  const stepQuestions = useMemo(
    () => questions.filter(q => STEPS[step]?.dimensions.includes(q.dimension)),
    [questions, step],
  );

  const setValue = useCallback((key: string, value: unknown) => {
    setAnswers(prev => ({ ...prev, [key]: value }));
  }, []);

  /** 多选切换；focusAreas 变化时联动补齐/裁剪 areaLevels，保证后端联动校验通过 */
  const toggleMulti = useCallback((key: string, value: string) => {
    setAnswers(prev => {
      const cur = (prev[key] as string[] | undefined) ?? [];
      const next = cur.includes(value) ? cur.filter(x => x !== value) : [...cur, value];
      if (key !== KEY_FOCUS_AREAS) {
        return { ...prev, [key]: next };
      }
      const levels = { ...((prev.areaLevels as Record<string, string> | undefined) ?? {}) };
      for (const v of next) if (!levels[v]) levels[v] = DEFAULT_LEVEL;
      for (const k of Object.keys(levels)) if (!next.includes(k)) delete levels[k];
      return { ...prev, [key]: next, areaLevels: levels };
    });
  }, []);

  /** 步内校验：必答单选有值、必答多选非空、领域水平自评完整 */
  const validateStep = useCallback((): boolean => {
    for (const q of stepQuestions) {
      if (!q.required) continue;
      const value = answers[q.key];
      if (q.type === 'SINGLE' && !value) {
        toast(`请选择：${q.label}`, 'error');
        return false;
      }
      if (q.type === 'MULTI' && (!Array.isArray(value) || value.length === 0)) {
        toast(`请至少选择一项：${q.label}`, 'error');
        return false;
      }
      if (q.type === 'AREA_LEVELS') {
        const areas = (answers[KEY_FOCUS_AREAS] as string[] | undefined) ?? [];
        const levels = (value as Record<string, string> | undefined) ?? {};
        if (areas.some(a => !levels[a])) {
          toast('请完成各关注领域的水平自评', 'error');
          return false;
        }
      }
    }
    return true;
  }, [stepQuestions, answers]);

  const next = () => {
    if (validateStep()) setStep(s => Math.min(s + 1, STEPS.length - 1));
  };
  const prev = () => setStep(s => Math.max(s - 1, 0));

  const submit = useCallback(async () => {
    if (!validateStep()) return;
    setSaving(true);
    try {
      await QuestionnaireApi.submit(answers);
      toast('画像已更新，AI 同事下次发言即可感知', 'success');
    } catch (e: any) {
      toast(e.message || '保存失败', 'error');
    } finally {
      setSaving(false);
    }
  }, [answers, validateStep]);

  if (!loaded) return null;

  return (
    <div className="app-shell">
      <Sidebar />

      <section className="page page--editorial">
        <header className="page__head">
          <div className="page__head-meta">
            <span className="page__eyebrow">Profile</span>
            <span className="page__head-rule" aria-hidden />
          </div>
          <div className="page__head-main">
            <h1 className="page__title-serif">个人画像</h1>
            <p className="page__lead">
              填写问卷生成你的学习画像，AI 同事将据此调整讨论深度与讲解方式；可随时重填，重填即重置画像基线
            </p>
          </div>
        </header>

        <div className="page__body">
          <ol className="profile-steps">
            {STEPS.map((s, i) => (
              <li key={s.title}
                  className={`profile-steps__item${i === step ? ' is-active' : ''}${i < step ? ' is-done' : ''}`}>
                <span className="profile-steps__index">{i + 1}</span>
                <span className="profile-steps__name">{s.title}</span>
              </li>
            ))}
          </ol>

          <div className="profile-form">
            {stepQuestions.map(q => (
              <QuestionBlock
                key={q.key}
                question={q}
                questions={questions}
                answers={answers}
                setValue={setValue}
                toggleMulti={toggleMulti}
              />
            ))}
          </div>

          <div className="profile-actions">
            <button type="button" className="btn" onClick={prev} disabled={step === 0}>
              上一步
            </button>
            {step < STEPS.length - 1 ? (
              <button type="button" className="btn btn--brand" onClick={next}>下一步</button>
            ) : (
              <button type="button" className="btn btn--brand" onClick={submit} disabled={saving}>
                {saving ? '保存中…' : '保存画像'}
              </button>
            )}
          </div>
        </div>
      </section>
    </div>
  );
}
