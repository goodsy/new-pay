import { menus, roles } from '../data'
import { useUi } from '../context'
import { PageHeading } from '../components/common'

const permCols = ['업무', '조회', '등록·수정', '승인', '내보내기'] as const
const permLabels = ['조회', '등록 수정', '승인', '내보내기'] as const

/** app.js accessView() — 권한 설정 UI 시연(실제 접근 통제 아님) */
export function AccessPage() {
  const ui = useUi()
  const role = ui.role
  return (
    <>
      <PageHeading t="계정·권한" s="업무 역할, 조회 범위, 실행 권한을 구분해 관리하세요.">
        <button className="btn primary" onClick={() => ui.toast('현재 탭의 데모 권한을 저장했어요. 실제 권한에는 반영되지 않습니다.')}>
          데모 권한 저장
        </button>
      </PageHeading>
      <div className="card">
        <div className="tabs">
          <button className="on">역할·권한</button>
          <button onClick={() => ui.openPanel({ kind: 'accounts' })}>계정 관리</button>
          <button onClick={() => ui.openPanel({ kind: 'audit' })}>접속·감사 이력</button>
        </div>
        <div className="rolebar">
          <b>역할 선택</b>
          <select aria-label="역할 선택" value={role} onChange={(e) => ui.setRole(e.target.value)}>
            {roles.map((r) => (
              <option key={r}>{r}</option>
            ))}
          </select>
          <span className="subtle">메뉴 노출과 실행 권한을 별도로 설정합니다</span>
        </div>
        <div className="tablewrap">
          <table className="matrix">
            <thead>
              <tr>
                {permCols.map((s) => (
                  <th key={s}>{s}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {menus.slice(1, 9).map((m, i) => (
                <tr key={m[0]}>
                  <td>{m[1]}</td>
                  {[0, 1, 2, 3].map((j) => {
                    const key = role + i + j
                    const checked = ui.perms[key] ?? (j === 0 || (role === '승인 관리자' && j === 2) || (role === '정산 담당자' && i === 4 && j === 1))
                    return (
                      <td key={j}>
                        <input type="checkbox" aria-label={`${m[1]} ${permLabels[j]}`} checked={checked} onChange={(e) => ui.setPerm(key, e.target.checked)} />
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
      </div>
    </>
  )
}
