import { THEME_OPTIONS, type ThemePreference } from '../lib/theme'

/** 사이드바 아래 테마 선택(시스템·라이트·다크 — D-ADMIN-HOME-001). 선택 상태는 aria-pressed, 모양은 workspace.css `.themetoggle`. */
export function ThemeToggle({ value, onChange }: { value: ThemePreference; onChange: (value: ThemePreference) => void }) {
  return (
    <div className="themetoggle" role="group" aria-label="화면 테마">
      {THEME_OPTIONS.map((option) => (
        <button key={option.value} type="button" aria-pressed={value === option.value} className={value === option.value ? 'on' : undefined} onClick={() => onChange(option.value)}>
          {option.label}
        </button>
      ))}
    </div>
  )
}
