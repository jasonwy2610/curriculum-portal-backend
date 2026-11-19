import React, {useState} from 'react';
import {selectCurriculumTrack} from './curriculum-tracks.js';
import {groupedTree} from './curriculum-ui.js';

export default function CurriculumTrackPreview({curriculum, summary, initialCode = ''}) {
  const [code, setCode] = useState(initialCode);
  const chosen = curriculum.specializations?.some(s => s.code === code) ? code : curriculum.specializations?.[0]?.code ?? '';
  let view, error;
  try { view = selectCurriculumTrack(curriculum, chosen); } catch (e) { error = e.message; }
  const official = summary?.[chosen] ?? curriculum.creditPlansBySpecialization?.[chosen];
  return <section className="tree-preview">
    <label className="field-label">Cây chuyên ngành<select value={chosen} onChange={event => setCode(event.target.value)}>
      <option value="" disabled>Chọn chuyên ngành</option>{(curriculum.specializations ?? []).map(spec =>
        <option key={spec.code} value={spec.code}>{spec.name}</option>)}
    </select></label>
    {error && <p role="status">{error}</p>}
    {!chosen && <p>CTĐT chưa có chuyên ngành.</p>}
    {view && chosen && <>
      <p>Tổng tín chỉ yêu cầu đã kiểm tra: {official?.requiredTotal ?? 'Chưa kiểm tra'} · Bắt buộc: {official?.mandatoryCredits ?? 'Chưa kiểm tra'}</p>
      {!view.semesters?.length && <p>Chưa có học phần trong cây chuyên ngành.</p>}
      {groupedTree(view).map(semester => <div className="catalog-semester" key={semester.semesterNumber}>
        <h4>Học kỳ {semester.semesterNumber}</h4>{semester.sections.map(section => <section key={section.kind}>
          <h5>{section.label}</h5><div className="table-wrap"><table>
            <thead><tr><th>Học phần</th><th>Tín chỉ</th><th>Tiên quyết</th></tr></thead>
            <tbody>{section.courses.map((course, i) => <tr key={course.courseCode + '-' + i}>
              <td><b>{course.courseName || course.courseCode}</b><small className="tree-code">{course.courseCode}</small></td>
              <td>{course.credits ?? '—'}</td><td>{course.prerequisiteCourseCodes?.join(', ') || 'Không có'}</td>
            </tr>)}</tbody>
          </table></div>
        </section>)}
      </div>)}
    </>}
  </section>;
}
