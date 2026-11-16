import { useState } from 'react'
import { Card } from '../components/ui/Card'
import { PageHeading } from '../components/ui/PageHeading'
import { TagButton } from '../components/ui/Tag'
import { LEGACY_MENUS, legacyDestination } from '../data/legacy'
import { pageTitle } from '../data/menus'
import { useWorkspace } from '../store/WorkspaceProvider'

/**
 * 메뉴 개편 지도 (#map)
 * 기존 관리자 메뉴 165개 → 새 업무 영역 매핑표. 검색은 메뉴명(트리 접두어 포함)+MENU_ID 부분일치(대소문자 구분, 원본 filterMap).
 */
export function MenuMapPage() {
  const { go } = useWorkspace()
  const [q, setQ] = useState('')
  const rows = LEGACY_MENUS.filter((r) => (r.A + r.B).includes(q))

  return (
    <>
      <PageHeading title="메뉴 개편 지도" description="첨부 파일의 내부 관리자 메뉴 165개를 보존하고 새 업무 영역으로 연결했습니다." />
      <Card>
        <div className="mapnote">
          <b>3단계 메뉴 탐색 → 업무 영역 + 화면 탭 + 상세 패널</b>
          <br />
          아래는 1차 재배치 제안입니다. 원본 상위 메뉴 추론·중복 명칭은 그대로 표시하고, 실제 기능 통합 여부와 권한은 별도 확인이 필요합니다. 로그인·로그아웃은 공통 인증으로 분리합니다.
        </div>
        <div className="filters">
          <input aria-label="레거시 메뉴 검색" placeholder="기존 메뉴명 또는 MENU_ID 검색" value={q} onChange={(e) => setQ(e.target.value)} />
        </div>
        <div id="maprows">
          <div className="tablewrap">
            <table>
              <thead>
                <tr>
                  <th>기존 메뉴 / MENU_ID</th>
                  <th>레벨</th>
                  <th>개편 후 업무</th>
                  <th>검토 사항</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => {
                  const dest = legacyDestination(r)
                  return (
                    <tr key={r.B}>
                      <td>
                        {r.A}
                        <small>{r.B}</small>
                      </td>
                      <td>{r.C}</td>
                      <td>
                        <TagButton onClick={() => go(dest)}>{`${r.C === '0' ? '공통 인증' : pageTitle(dest)} →`}</TagButton>
                      </td>
                      <td>{r.G || '업무 기준 재배치 제안'}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        </div>
      </Card>
    </>
  )
}
