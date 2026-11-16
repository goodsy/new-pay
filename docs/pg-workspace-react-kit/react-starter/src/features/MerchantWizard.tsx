import { useState, type FormEvent } from 'react';
import { Button } from '../components/ui/Button';
import { DetailGrid, FormRow, Notice, TextField, WizardSteps } from '../components/ui/Panel';
import { useWorkspace } from '../store/WorkspaceProvider';

const STEPS = ['사업자 정보', '계약·원천사', '가맹점 설정', '검토·등록'];

type FormState = Partial<Record<'name' | 'biz' | 'owner' | 'contract' | 'source' | 'service' | 'cycle' | 'fee', string>>;

/**
 * 사업자 등록 4단계 위저드 (Drawer)
 * - 단계마다 <form> 을 새로 마운트(key=step)하여 네이티브 검증(required/pattern/min/max)을 그대로 사용
 * - 다음 단계 이동 시 FormData 를 누적, 마지막 단계에서 merchant 행 추가 후 목록으로 이동
 */
export function MerchantWizard() {
  const { addRow, go, toast } = useWorkspace();
  const [step, setStep] = useState(0);
  const [form, setForm] = useState<FormState>({});

  const onSubmit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const next = { ...form, ...(Object.fromEntries(new FormData(e.currentTarget)) as FormState) };
    setForm(next);
    if (step < 3) {
      setStep(step + 1);
      return;
    }
    const mid = 'demo' + Date.now().toString().slice(-6);
    addRow('merchant', [next.name ?? '', mid, next.biz ?? '', next.source ?? '', '심사 중', next.owner ?? '']);
    go('merchant');
    toast('사업자를 등록하고 데모 MID ' + mid + '를 발급했어요');
  };

  return (
    <>
      <div className="eyebrow">MERCHANT ONBOARDING</div>
      <h2>사업자 등록</h2>
      <WizardSteps labels={STEPS} current={step} />
      <form key={step} onSubmit={onSubmit}>
        {step === 0 && (
          <>
            <TextField label="사업자명 *" name="name" required defaultValue={form.name ?? ''} placeholder="예: 모노마켓" />
            <TextField
              label="사업자등록번호 *"
              name="biz"
              required
              pattern="[0-9]{3}-[0-9]{2}-[0-9]{5}"
              defaultValue={form.biz ?? ''}
              placeholder="123-45-67890"
            />
            <TextField label="담당자 *" name="owner" required defaultValue={form.owner ?? ''} />
          </>
        )}
        {step === 1 && (
          <>
            <FormRow label="계약 유형">
              <select name="contract" defaultValue={form.contract}>
                <option>일반 가맹점</option>
                <option>오픈마켓</option>
              </select>
            </FormRow>
            <FormRow label="원천사">
              <select name="source" defaultValue={form.source}>
                <option>NICE</option>
                <option>키움페이</option>
              </select>
            </FormRow>
            <Notice>
              사업자 등록 → 계약·심사 → 원천사 가맹점번호 연결 → 내부 MID 발급 순서로 진행합니다. 원천사 번호 발급은 별도
              연동 대상입니다.
            </Notice>
          </>
        )}
        {step === 2 && (
          <>
            <TextField label="서비스명 *" name="service" required defaultValue={form.service || form.name || ''} />
            <FormRow label="정산 주기">
              <select name="cycle" defaultValue={form.cycle}>
                <option>D+3</option>
                <option>반월</option>
                <option>월 정산</option>
              </select>
            </FormRow>
            <TextField
              label="카드 수수료율 (%, VAT 별도) *"
              name="fee"
              type="number"
              min="0"
              max="100"
              step="0.01"
              required
              defaultValue={form.fee || '2.4'}
            />
          </>
        )}
        {step === 3 && (
          <>
            <DetailGrid
              items={[
                { label: '사업자명', value: form.name },
                { label: '사업자번호', value: form.biz },
                { label: '원천사', value: form.source },
                { label: '정산 주기', value: form.cycle },
                { label: '서비스명', value: form.service },
                { label: '수수료', value: `${form.fee}% · VAT 별도` },
              ]}
            />
            <Notice>
              내부 MID는 데모 번호로 생성됩니다. 원천사 번호 발급·중복 사업자 확인·심사는 실제 연동 범위입니다. 등록 후
              새로고침하면 초기화됩니다.
            </Notice>
          </>
        )}
        <div className="actions" style={{ marginTop: 30 }}>
          {step > 0 && <Button onClick={() => setStep(step - 1)}>이전</Button>}
          <Button type="submit" variant="primary">
            {step === 3 ? '등록하고 데모 MID 발급' : '다음 단계 →'}
          </Button>
        </div>
      </form>
    </>
  );
}
