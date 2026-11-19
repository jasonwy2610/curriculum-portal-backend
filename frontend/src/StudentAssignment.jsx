import React, {useEffect, useState} from 'react';
import AcademicIdentity from './AcademicIdentity.jsx';
import {assignmentCandidates, assignmentPayload} from './curriculum-ui.js';

export default function StudentAssignment({token, request, notify}) {
  const [query, setQuery] = useState(''), [students, setStudents] = useState([]);
  const [programs, setPrograms] = useState([]), [studentId, setStudentId] = useState('');
  const [curriculumId, setCurriculumId] = useState(''), [code, setCode] = useState('');
  const [error, setError] = useState(''), [busy, setBusy] = useState(false);
  const [majors, setMajors] = useState([]), [loading, setLoading] = useState(true), [searched, setSearched] = useState(false);
  const [majorFilter, setMajorFilter] = useState(''), [cohortFilter, setCohortFilter] = useState(''), [trackFilter, setTrackFilter] = useState('');
  const [reloadKey, setReloadKey] = useState(0);
  const student = students.find(item => item.id === studentId);
  const program = programs.find(item => item.id === curriculumId);
  useEffect(() => {
    let live = true;
    setLoading(true);
    Promise.all([request('/api/curriculums', {token}), request('/api/majors', {token})]).then(([programs, majors]) => {
      if (live) { setPrograms(programs ?? []); setMajors(majors ?? []); }
    }).catch(error => { if (live) setError(error.message); }).finally(() => { if (live) setLoading(false); });
    return () => { live = false; };
  }, [token, request, reloadKey]);
  async function search(event) {
    event.preventDefault(); setError(''); setBusy(true);
    try {
      const result = await request('/api/v1/admin/students?q=' + encodeURIComponent(query) + '&size=50', {token});
      setStudents(result.items ?? []); setSearched(true); setStudentId(''); setCurriculumId(''); setCode('');
    } catch (error) { setError(error.message); } finally { setBusy(false); }
  }
  async function save(event) {
    event.preventDefault();
    if (!window.confirm('Gán cố định CTĐT và chuyên ngành cho sinh viên này?')) return;
    setError(''); setBusy(true);
    try {
      const body = assignmentPayload(student, program, code);
      await request('/api/v1/admin/students/' + studentId + '/curriculum-assignment',
        {token, method: 'PUT', body});
      setStudents(students.map(item => item.id === studentId
        ? {...item, curriculumId, chosenSpecializationCode: code} : item));
      notify('Đã gán cố định CTĐT và chuyên ngành');
    } catch (error) { setError(error.message); } finally { setBusy(false); }
  }
  const fixed = Boolean(student?.curriculumId && student?.chosenSpecializationCode);
  const visibleStudents = students.filter(s => (!majorFilter || s.majorId === majorFilter)
    && (!cohortFilter || s.cohort === cohortFilter) && (!trackFilter || s.chosenSpecializationCode === trackFilter));
  function filter(setter, value) { setter(value); setStudentId(''); setCurriculumId(''); setCode(''); }
  return <section className="panel huf-feature-panel"><h3>Gán CTĐT và chuyên ngành từ năm nhất</h3>
    {loading && <p role="status">Đang tải ngành và các phiên bản CTĐT...</p>}
    <form onSubmit={search} className="huf-inline-actions">
      <input aria-label="Tìm sinh viên để gán CTĐT" value={query} onChange={event => setQuery(event.target.value)}
        placeholder="Mã sinh viên, họ tên hoặc email"/>
      <button className="outline" disabled={busy}>Tìm sinh viên</button>
    </form>
    <div className="form-grid"><label className="field-label">Ngành trong kết quả tra cứu<select value={majorFilter} onChange={e => filter(setMajorFilter, e.target.value)}>
      <option value="">Tất cả ngành</option>{majors.map(m => <option key={m.id} value={m.id}>{m.name}</option>)}
    </select></label><label className="field-label">Khóa trong kết quả tra cứu<select value={cohortFilter} onChange={e => filter(setCohortFilter, e.target.value)}>
      <option value="">Tất cả khóa</option>{[...new Set(students.map(s => s.cohort).filter(Boolean))].sort().map(c => <option key={c}>{c}</option>)}
    </select></label><label className="field-label">Chuyên ngành trong kết quả tra cứu<select value={trackFilter} onChange={e => filter(setTrackFilter, e.target.value)}>
      <option value="">Tất cả chuyên ngành</option>{majors.filter(m => !majorFilter || m.id === majorFilter).flatMap(m =>
        (m.specializations ?? []).map(s => <option key={m.id + s.code} value={s.code}>{m.name} · {s.name}</option>))}
    </select></label></div>
    <p className="muted">Các bộ lọc thu hẹp tối đa 50 kết quả đang tải. Tìm bằng mã sinh viên để tra cứu chính xác.</p>
    {searched && !visibleStudents.length && <p role="status">Không tìm thấy sinh viên phù hợp. Hãy đổi từ khóa hoặc bộ lọc.</p>}
    {student && <AcademicIdentity student={student} curriculum={program} majors={majors}/>}
    <form onSubmit={save} className="form-grid">
      <label className="field-label">Sinh viên<select required disabled={busy} value={studentId} onChange={event => {
        const person = students.find(item => item.id === event.target.value);
        setStudentId(event.target.value); setCurriculumId(person?.curriculumId ?? '');
        setCode(person?.chosenSpecializationCode ?? '');
      }}><option value="">Chọn sinh viên</option>
        {visibleStudents.map(item => <option key={item.id} value={item.id}>{item.studentCode} · {item.fullName} · {item.cohort}</option>)}
      </select></label>
      <label className="field-label">CTĐT đã công bố<select required disabled={busy || Boolean(student?.curriculumId) || !student} value={curriculumId}
        onChange={event => { setCurriculumId(event.target.value); setCode(''); }}>
        <option value="">Chọn đúng ngành và khóa</option>
        {assignmentCandidates(programs, student)
          .map(item => <option key={item.id} value={item.id}>{item.curriculumCode} · v{item.version}</option>)}
      </select></label>
      <label className="field-label">Chuyên ngành cố định<select required disabled={busy || Boolean(student?.chosenSpecializationCode) || !program} value={code}
        onChange={event => setCode(event.target.value)}>
        <option value="">Chọn chuyên ngành</option>
        {(program?.specializations ?? []).map(item => <option key={item.code} value={item.code}
          disabled={Object.keys(program.specializationTrees ?? {}).length > 0 && !program.specializationTrees[item.code]}>{item.name}</option>)}
      </select></label>
      {!fixed && <button className="primary" disabled={busy || loading || !code || !curriculumId}>Gán cố định</button>}
    </form>
    {fixed && <p>Sinh viên đã được gán phiên bản và chuyên ngành cố định.</p>}
    {error && <div role="alert"><p className="form-error">{error}</p>
      <button className="outline" disabled={busy || loading} onClick={() => {setError('');setReloadKey(value => value + 1);}}>Tải lại danh mục CTĐT</button></div>}
  </section>;
}
