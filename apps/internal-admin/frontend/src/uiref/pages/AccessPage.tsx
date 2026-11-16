import { useState } from 'react'
import { Button } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { PageHeading } from '../components/ui/PageHeading'
import { Tabs } from '../components/ui/Tabs'
import { MENUS } from '../data/menus'
import { AccountsPanel, AuditPanel } from '../features/AccessPanels'
import { useWorkspace } from '../store/WorkspaceProvider'

const ROLES = ['운영 담당자', '정산 담당자', '승인 관리자', '조회 전용'] as const
const PERM_COLS = ['조회', '등록·수정', '승인', '내보내기'] as const
const PERM_ARIA = ['조회', '등록 수정', '승인', '내보내기'] as const

/** 기본 체크 규칙(원본): 조회는 전 역할 허용, 승인 관리자=승인, 정산 담당자=정산·지급 등록·수정 */
const defaultChecked = (role: string, rowIdx: number, colIdx: number) =>
  colIdx === 0 || (role === '승인 관리자' && colIdx === 2) || (role === '정산 담당자' && rowIdx === 4 && colIdx === 1)

/**
 * 계정·권한 (#access) — 권한 설정 UI 시연이다. 체크·저장은 메모리/토스트뿐이고 실제 접근 통제는 서버 책임이다.
 * heading → card[ tabs(역할·권한 고정, 나머지 2개는 Drawer 오픈) → rolebar → 권한 매트릭스(table.matrix) → mapnote ]
 * 매트릭스 행 = 업무 메뉴 8개(MENUS[1..8]), 열 = 조회/등록·수정/승인/내보내기
 */
export function AccessPage() {
  const { toast, openDrawer } = useWorkspace()
  const [role, setRole] = useState<string>(ROLES[0])
  const [perms, setPerms] = useState<Readonly<Record<string, boolean>>>({})

  return (
    <>
      <PageHeading
        title="계정·권한"
        description="업무 역할, 조회 범위, 실행 권한을 구분해 관리하세요."
        actions={
          <Button variant="primary" onClick={() => toast('현재 탭의 데모 권한을 저장했어요. 실제 권한에는 반영되지 않습니다.')}>
            데모 권한 저장
          </Button>
        }
      />
      <Card as="div">
        <Tabs
          items={['역할·권한', '계정 관리', '접속·감사 이력']}
          active={0}
          onSelect={(i) => {
            if (i === 1) openDrawer(<AccountsPanel />)
            if (i === 2) openDrawer(<AuditPanel />)
          }}
        />
        <div className="rolebar">
          <b>역할 선택</b>
          <select value={role} onChange={(e) => setRole(e.target.value)}>
            {ROLES.map((r) => (
              <option key={r}>{r}</option>
            ))}
          </select>
          <span className="subtle">메뉴 노출과 실행 권한을 별도로 설정합니다</span>
        </div>
        <div className="tablewrap">
          <table className="matrix">
            <thead>
              <tr>
                {['업무', ...PERM_COLS].map((s) => (
                  <th key={s}>{s}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {MENUS.slice(1, 9).map((m, i) => (
                <tr key={m.id}>
                  <td>{m.label}</td>
                  {PERM_ARIA.map((aria, j) => {
                    const key = `${role}${i}${j}`
                    return (
                      <td key={aria}>
                        <input
                          type="checkbox"
                          aria-label={`${m.label} ${aria}`}
                          checked={perms[key] ?? defaultChecked(role, i, j)}
                          onChange={(e) => setPerms((p) => ({ ...p, [key]: e.target.checked }))}
                        />
                      </td>
                    )
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="mapnote">
          설계 원칙: 요청자와 승인자 분리 · 민감정보 마스킹 · 가맹점별 데이터 범위 제한 · 변경 사유와 감사 이력 보관
          <br />
          현재 화면은 권한 설정 UI 시연이며, 실제 접근 통제는 서버에서 적용해야 합니다.
        </div>
      </Card>
    </>
  )
}
