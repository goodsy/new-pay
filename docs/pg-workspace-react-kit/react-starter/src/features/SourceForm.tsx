import type { FormEvent } from 'react';
import { Button } from '../components/ui/Button';
import { FormRow, Notice, TextField } from '../components/ui/Panel';
import { useWorkspace } from '../store/WorkspaceProvider';

/** 원천사 등록 Drawer — 네이티브 required 검증 사용 */
export function SourceForm() {
  const { addRow, go, toast } = useWorkspace();
  const onSubmit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const d = new FormData(e.currentTarget);
    addRow('source', [
      String(d.get('name')),
      'SRC-DEMO-' + Date.now().toString().slice(-5),
      String(d.get('method')),
      String(d.get('mid')),
      '설정 대기',
      '김 운영',
    ]);
    go('source');
    toast('원천사 데모 기준정보를 등록했어요');
  };
  return (
    <>
      <h2>원천사 등록</h2>
      <p className="subtle">기준정보와 외부 발급 번호를 구분해 관리합니다.</p>
      <form onSubmit={onSubmit}>
        <TextField label="원천사명" name="name" required />
        <FormRow label="결제수단">
          <select name="method">
            <option>신용카드</option>
            <option>가상계좌</option>
            <option>문화상품권</option>
          </select>
        </FormRow>
        <TextField label="원천사 발급 가맹점번호" name="mid" required />
        <Notice>실제 채번은 원천사 연동이 필요합니다. 여기서는 입력한 발급 번호를 데모 기준정보에 연결합니다.</Notice>
        <br />
        <Button type="submit" variant="primary">
          데모 등록
        </Button>
      </form>
    </>
  );
}
