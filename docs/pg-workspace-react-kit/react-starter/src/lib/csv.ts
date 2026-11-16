import type { Row } from '../data/types';

/** 원본 exportRows(): BOM + CRLF, 첫 컬럼은 "대표명 / 식별키" 로 합친다. Excel 한글 깨짐 방지용 BOM 필수 */
export function downloadRowsCsv(fileName: string, cols: readonly string[], rows: Row[]): void {
  const lines = [cols, ...rows.map((r) => [`${r[0]} / ${r[1]}`, ...r.slice(2)])];
  const csv =
    '\ufeff' + lines.map((line) => line.map((s) => '"' + String(s).replace(/"/g, '""') + '"').join(',')).join('\r\n');
  const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  a.click();
  URL.revokeObjectURL(url);
}
