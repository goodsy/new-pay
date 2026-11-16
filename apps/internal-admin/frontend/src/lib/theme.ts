import { useEffect, useState } from 'react'

/**
 * 화면 테마(D-ADMIN-HOME-001 — 라이트(참고 시안 그대로) + 다크 둘 다).
 * 기본은 OS 설정(prefers-color-scheme)을 따르고, 사이드바 아래에서 시스템·라이트·다크를 고를 수 있다.
 * 고른 값만 브라우저 저장소에 남긴다(사용자가 직접 고를 때만 — "시스템"을 고르면 지운다). 저장소를 쓸 수 없는 환경(사생활 모드·차단)은 조용히 무시한다.
 * 결정된 테마는 `<html data-theme="light|dark">`로만 알린다(inline style 없음 — CSP style-src 'self'). 색은 CSS(styles.css·workspace.css).
 */
export type ThemePreference = 'system' | 'light' | 'dark'
export type Theme = 'light' | 'dark'

export const THEME_STORAGE_KEY = 'paycore-admin-theme'
const DARK_QUERY = '(prefers-color-scheme: dark)'

export function readThemePreference(): ThemePreference {
  try {
    const value = window.localStorage.getItem(THEME_STORAGE_KEY)
    return value === 'light' || value === 'dark' ? value : 'system'
  } catch {
    return 'system'
  }
}

function writeThemePreference(preference: ThemePreference) {
  try {
    if (preference === 'system') window.localStorage.removeItem(THEME_STORAGE_KEY)
    else window.localStorage.setItem(THEME_STORAGE_KEY, preference)
  } catch {
    // 저장할 수 없으면 이번 화면에서만 적용한다.
  }
}

const media = (): MediaQueryList | null => (typeof window.matchMedia === 'function' ? window.matchMedia(DARK_QUERY) : null)

export function resolveTheme(preference: ThemePreference, systemDark = media()?.matches ?? false): Theme {
  return preference === 'system' ? (systemDark ? 'dark' : 'light') : preference
}

/**
 * 첫 그리기 전에 한 번(main.tsx, createRoot 앞 — 리뷰 MINOR-3 테마 깜빡임): 저장된 선택·OS 설정으로 `<html data-theme>`를 맞춘다.
 * CSP script-src 'self'라 index.html inline script가 아니라 번들 첫 줄에서 한다. 저장소·matchMedia가 없거나 막혀도 던지지 않는다(readThemePreference·media).
 */
export function applyStoredTheme(): void {
  document.documentElement.setAttribute('data-theme', resolveTheme(readThemePreference()))
}

/** 앱 최상위에서 한 번: 고른 값(저장소)·OS 설정으로 `<html data-theme>`를 맞추고, 시스템 모드면 OS 변경도 따른다. */
export function useTheme(): [ThemePreference, (preference: ThemePreference) => void] {
  const [preference, setPreference] = useState<ThemePreference>(readThemePreference)
  const [systemDark, setSystemDark] = useState(() => media()?.matches ?? false)

  useEffect(() => {
    const list = media()
    if (!list) return
    const onChange = (event: MediaQueryListEvent) => setSystemDark(event.matches)
    list.addEventListener('change', onChange)
    return () => list.removeEventListener('change', onChange)
  }, [])

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', resolveTheme(preference, systemDark))
  }, [preference, systemDark])

  const choose = (next: ThemePreference) => {
    writeThemePreference(next)
    setPreference(next)
  }
  return [preference, choose]
}

const OPTIONS: { value: ThemePreference; label: string }[] = [
  { value: 'system', label: '시스템' },
  { value: 'light', label: '라이트' },
  { value: 'dark', label: '다크' },
]
export const THEME_OPTIONS = OPTIONS
