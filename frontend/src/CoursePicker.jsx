import React, {useEffect, useId, useMemo, useState} from 'react';
import './curriculum-builder.css';

// Selection belongs to the picker, never to the currently visible search page.
export default function CoursePicker({label, items = [], value = [], onApply, disabled = false,
  reasonFor = () => '', warningFor = () => '', showSelectionCredits = false, showSemester = false, applyLabel = 'Áp dụng lựa chọn'}) {
  const id = useId(), key = JSON.stringify(value);
  const [selected, setSelected] = useState(value), [query, setQuery] = useState('');
  const [credits, setCredits] = useState(''), [department, setDepartment] = useState(''), [page, setPage] = useState(0);
  useEffect(() => { setSelected(JSON.parse(key)); }, [key]);
  const filtered = useMemo(() => items.filter(c =>
    `${c.courseCode} ${c.courseName}`.toLocaleLowerCase('vi').includes(query.trim().toLocaleLowerCase('vi')) &&
    (!credits || String(c.credits) === credits) && (!department || c.department === department)), [items, query, credits, department]);
  const pages = Math.max(1, Math.ceil(filtered.length / 8)), current = Math.min(page, pages - 1);
  const selectedCredits = [...new Set(selected)].map(code=>items.find(c=>c.courseCode===code)?.credits);
  const creditTotal = selectedCredits.every(c=>Number.isInteger(c)&&c>0)?selectedCredits.reduce((sum,c)=>sum+c,0):null;
  function toggle(code) { setSelected(old => old.includes(code) ? old.filter(c => c !== code) : [...old, code]); }
  return <fieldset className="cb-picker" disabled={disabled}><legend>{label}</legend>
    <div className="cb-picker-filters">
      <label htmlFor={id}>Tìm mã hoặc tên học phần<input id={id} value={query} onChange={e => {setQuery(e.target.value); setPage(0);}}/></label>
      <label>Số tín chỉ<select value={credits} onChange={e => {setCredits(e.target.value); setPage(0);}}>
        <option value="">Tất cả tín chỉ</option>{[...new Set(items.map(c => c.credits).filter(Number.isFinite))].sort((a,b)=>a-b).map(c => <option key={c}>{c}</option>)}
      </select></label>
      {items.some(c => c.department) && <label>Khoa / bộ môn<select value={department} onChange={e => {setDepartment(e.target.value); setPage(0);}}>
        <option value="">Tất cả khoa / bộ môn</option>{[...new Set(items.map(c => c.department).filter(Boolean))].sort().map(d => <option key={d}>{d}</option>)}
      </select></label>}
    </div>
    <div className="cb-picker-list">{filtered.slice(current * 8, current * 8 + 8).map(course => {
      const reason = reasonFor(course), warning = warningFor(course), checked = selected.includes(course.courseCode);
      return <label className="cb-picker-item" key={course.courseCode}>
        <input type="checkbox" checked={checked} disabled={Boolean(reason) && !checked}
          onChange={() => toggle(course.courseCode)} aria-describedby={reason ? id + course.courseCode : undefined}/>
        <span><b>{course.courseCode}</b> · {course.courseName}<small>{course.credits ?? 'Chưa có'} tín chỉ
          {showSemester && <span> · {Number.isInteger(course.semesterNumber)&&course.semesterNumber>0?'Học kỳ '+course.semesterNumber:'Chưa có học kỳ trong cây'}</span>}
          {reason && <span id={id + course.courseCode}> · {reason}</span>}
          {warning && <span className="go-conflict"> · {warning}</span>}</small></span>
      </label>;
    })}{!filtered.length && <p role="status">Không có học phần phù hợp bộ lọc.</p>}</div>
    <div className="cb-picker-footer"><span role="status">Đã chọn {selected.length} học phần{showSelectionCredits && <> · Tổng {creditTotal ?? 'chưa đủ dữ liệu'} tín chỉ</>} · Trang {current + 1}/{pages}</span>
      <button type="button" className="outline" disabled={!current} onClick={() => setPage(current - 1)}>Trang trước</button>
      <button type="button" className="outline" disabled={current + 1 >= pages} onClick={() => setPage(current + 1)}>Trang sau</button>
      <button type="button" className="primary" onClick={() => onApply(selected)}>{applyLabel}</button></div>
    {selected.length > 0 && <div className="cb-selected">{selected.map(code => <button type="button" className="outline" key={code}
      aria-label={'Bỏ chọn ' + code} onClick={() => toggle(code)}>{code} ×</button>)}</div>}
    {disabled && <small>Chỉ xem: phiên bản đã khóa hoặc đang xử lý.</small>}
  </fieldset>;
}
