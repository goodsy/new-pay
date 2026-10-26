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
  return (
    <div role="dialog" aria-modal="true" aria-labelledby="one-time-code-title" className="one-time-code">
      <h2 id="one-time-code-title">등록 코드(1회 표시)</h2>
      <p className="code" translate="no">{grouped(code)}</p>
      <p>이 코드는 지금 한 번만 보입니다. 닫으면 다시 볼 수 없습니다. 별도 채널로 본인에게만 전달하세요.</p>
      <button type="button" onClick={onClose}>
        확인했습니다 — 닫기
      </button>
    </div>
  )
}
