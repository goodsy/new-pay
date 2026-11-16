import { Notice, Timeline } from '../components/ui/Panel';

/** 계정·권한 > "계정 관리" 탭 클릭 시 Drawer */
export function AccountsPanel() {
  return (
    <>
      <h2>계정 관리</h2>
      <Timeline
        items={[
          { title: '김 운영', desc: '운영 담당자 · 활성 · 전체 가맹점 조회' },
          { title: '이 정산', desc: '정산 담당자 · 활성 · 정산 수정 가능' },
          { title: '박 승인', desc: '승인 관리자 · 활성 · 지급 승인 가능' },
        ]}
      />
      <Notice>계정 발급·잠금·만료·MFA 및 역할 부여는 실제 인증 시스템 연동 대상입니다.</Notice>
    </>
  );
}

/** 계정·권한 > "접속·감사 이력" 탭 클릭 시 Drawer */
export function AuditPanel() {
  return (
    <>
      <h2>접속·감사 이력</h2>
      <Timeline
        items={[
          { title: '14:32 · 김 운영 · 가맹점 조회', desc: '조회 대상 mono000001 · 샘플' },
          { title: '10:20 · 이 정산 · 지급 검증', desc: 'ST-20261102-001 · 샘플' },
          { title: '09:00 · 김 운영 · 로그인', desc: '내부 관리자 · 샘플' },
        ]}
      />
    </>
  );
}
