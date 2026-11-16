import type { Row } from '../data/types'

/** 원본 exportRows() 의 CSV 본문: BOM + CRLF, 모든 칸 큰따옴표(내부 " 는 ""), 첫 컬럼은 "대표명 / 식별키" 로 합친다. */
export function rowsToCsv(cols: readonly string[], rows: readonly Row[]): string {
  const lines: (readonly string[])[] = [cols, ...rows.map((r) => [`${r[0]} / ${r[1]}`, ...r.slice(2)])]
  return '﻿' + lines.map((line) => line.map((s) => '"' + String(s).replace(/"/g, '""') + '"').join(',')).join('\r\n')
}

/** 현재 화면 데이터만 내려받는다(서버 요청 없음). Excel 한글 깨짐 방지용 BOM 필수 */
export function downloadRowsCsv(fileName: string, cols: readonly string[], rows: readonly Row[]): void {
  const url = URL.createObjectURL(new Blob([rowsToCsv(cols, rows)], { type: 'text/csv;charset=utf-8' }))
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  a.click()
  URL.revokeObjectURL(url)
}
