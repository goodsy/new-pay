import { useLayoutEffect, useRef } from 'react'
import { createPortal } from 'react-dom'
import { KeyRoundIcon } from './icons'
import { Button } from './ui/button'

/** 4자씩 띄어 읽기 쉽게(값 자체는 바꾸지 않는다). */
function grouped(code: string): string {
  return code.replace(/(.{4})(?=.)/g, '$1 ')
}

interface Props {
  code: string
  onClose: () => void
}

/**
 * 등록 코드 1회 표시(§4.7.7 — 승인 응답·계정 생성/재발급 응답의 코드). 코드는 부모 컴포넌트 상태에만 있고, 닫으면 부모가 그 상태를 지운다(저장소·URL·로그 없음).
 * 응답은 서버가 `no-store`로 보낸다. 별도 채널로 본인에게 전달하는 것은 운영 절차다.
 */
export function OneTimeCode({ code, onClose }: Props) {
  const overlay = useRef<HTMLDivElement>(null)

  // 리뷰 m-4: aria-modal이 실제가 되게, 열려 있는 동안 overlay 밖의 body 자식을 `inert`로 둔다(초점·클릭·보조 기술 차단). 닫히면 우리가 붙인 것만 되돌린다.
  useLayoutEffect(() => {
    const self = overlay.current
    const made = Array.from(document.body.children).filter((el) => el !== self && !el.hasAttribute('inert'))
    made.forEach((el) => el.setAttribute('inert', ''))
    return () => made.forEach((el) => el.removeAttribute('inert'))
  }, [])

  return createPortal(
    // ADMIN-UI-001: Radix Dialog(react-remove-scroll이 <style> 주입 — CSP 위반) 대신 body에 직접 붙는 일반 요소 overlay. 닫기 버튼에 처음 focus를 둔다.
    <div ref={overlay} className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="one-time-code-title"
        className="flex w-full max-w-md flex-col gap-4 rounded-xl border bg-background p-6 shadow-lg"
      >
        <h2 id="one-time-code-title" className="flex items-center gap-2 text-lg font-semibold">
          <KeyRoundIcon aria-hidden="true" className="size-5 text-primary" />
          등록 코드(1회 표시)
        </h2>
        <p className="rounded-md border bg-muted px-3 py-3 text-center font-mono text-lg tracking-wider break-all" translate="no">{grouped(code)}</p>
        <p className="text-sm text-muted-foreground">이 코드는 지금 한 번만 보입니다. 닫으면 다시 볼 수 없습니다. 별도 채널로 본인에게만 전달하세요.</p>
        <Button type="button" onClick={onClose} autoFocus className="self-end">
          확인했습니다 — 닫기
        </Button>
      </div>
    </div>,
    document.body,
  )
}
