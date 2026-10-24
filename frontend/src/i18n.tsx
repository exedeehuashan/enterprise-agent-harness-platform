import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';
import { ZH } from './i18n.zh';

export type Language = 'en' | 'zh';
const STORAGE_KEY = 'eahp.language';

/** Fills {name} placeholders. */
function fill(text: string, values?: Record<string, string | number>) {
  return values ? text.replace(/\{(\w+)\}/g, (m, k: string) => (k in values ? String(values[k]) : m)) : text;
}

export type Translate = (english: string, values?: Record<string, string | number>) => string;

interface I18n { language: Language; setLanguage: (l: Language) => void; t: Translate }

/**
 * Console languages (English and Chinese). The English text is the key; a missing translation falls back to English,
 * so a new string never shows up blank. Without a provider (tests, embedding) the console is English.
 */
const Context = createContext<I18n>({ language: 'en', setLanguage: () => undefined, t: fill });

function initial(): Language {
  try {
    const saved = localStorage.getItem(STORAGE_KEY);
    if (saved === 'en' || saved === 'zh') return saved;
  } catch { /* storage unavailable */ }
  return typeof navigator !== 'undefined' && navigator.language?.toLowerCase().startsWith('zh') ? 'zh' : 'en';
}

export function LanguageProvider({ children, language: fixed }: { children: ReactNode; language?: Language }) {
  const [language, setState] = useState<Language>(() => fixed ?? initial());
  const setLanguage = useCallback((l: Language) => {
    setState(l);
    try { localStorage.setItem(STORAGE_KEY, l); } catch { /* storage unavailable */ }
    document.documentElement.lang = l === 'zh' ? 'zh-CN' : 'en';
  }, []);
  const t = useCallback<Translate>((english, values) => fill(language === 'zh' ? ZH[english] ?? english : english, values), [language]);
  const value = useMemo(() => ({ language, setLanguage, t }), [language, setLanguage, t]);
  return <Context.Provider value={value}>{children}</Context.Provider>;
}

export function useI18n() { return useContext(Context); }
export function useT() { return useContext(Context).t; }

/** The language switch in the top bar. */
export function LanguageSwitch() {
  const { language, setLanguage } = useI18n();
  return (
    <button className="secondary" onClick={() => setLanguage(language === 'zh' ? 'en' : 'zh')} title={language === 'zh' ? 'Switch to English' : '切换到中文'}>
      {language === 'zh' ? 'English' : '中文'}
    </button>
  );
}
