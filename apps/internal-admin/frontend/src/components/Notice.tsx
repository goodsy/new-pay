import type { ReactNode } from 'react'
import { CircleAlertIcon, CircleCheckIcon, InfoIcon, TriangleAlertIcon } from './icons'
import { Alert, AlertDescription } from './ui/alert'

/** 오류 문구(role="alert" — 기존 `<p role="alert">` 자리). 문구는 호출자가 준 고정 문구 그대로. */
export function ErrorNotice({ children }: { children: ReactNode }) {
  return (
    <Alert role="alert" variant="destructive">
      <CircleAlertIcon aria-hidden="true" />
      <AlertDescription>{children}</AlertDescription>
    </Alert>
  )
}

/** 결과·상태 안내(role="status" — 기존 `<p role="status">` 자리). */
export function StatusNotice({ children, tone = 'info' }: { children: ReactNode; tone?: 'info' | 'success' }) {
  return (
    <Alert role="status">
      {tone === 'success' ? <CircleCheckIcon aria-hidden="true" className="text-success" /> : <InfoIcon aria-hidden="true" />}
      <AlertDescription>{children}</AlertDescription>
    </Alert>
  )
}

/** role 없는 주의 안내(기존 role 없는 `<p>` 자리 — alert·status 개수를 늘리지 않는다). */
export function WarningNote({ children }: { children: ReactNode }) {
  return (
    <Alert variant="warning">
      <TriangleAlertIcon aria-hidden="true" />
      <AlertDescription>{children}</AlertDescription>
    </Alert>
  )
}
