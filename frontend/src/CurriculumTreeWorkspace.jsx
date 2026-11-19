import React, {useEffect, useState} from 'react';
import {editableTrack, copyTrack, confirmTreeSwitch, canPublishTree, programStatus,
  courseRequirement, treeWarnings, assignTreeCourse, removeTreeCourse, treeValidationMessage} from './curriculum-ui.js';

const kinds = {MANDATORY_COMMON: 'Bắt buộc chung', MANDATORY_SPECIALIZATION: 'Bắt buộc chuyên ngành',
  ELECTIVE_SPECIALIZATION: 'Tự chọn chung', ELECTIVE_FREE: 'Tự chọn chung',
  GRADUATION: 'Phương án tốt nghiệp', CONDITIONAL: 'Điều kiện bổ sung'};

function CourseSelect({label, codes, items, change}) {
  return <label className="field-label">{label}<select multiple size={5} value={codes ?? []}
    onChange={event => change(Array.from(event.target.selectedOptions, option => option.value))}>
    {items.map(course => <option key={course.courseCode} value={course.courseCode}>
      {course.courseCode} · {course.courseName}</option>)}
  </select></label>;
}

export default function CurriculumTreeWorkspace({token, request, notify, Field, initialId = '', onDirtyChange = () => {}, onProgramSelected = () => {}}) {
  const [items, setItems] = useState([]), [catalog, setCatalog] = useState([]);
  const [id, setId] = useState(initialId), [detail, setDetail] = useState(null), [track, setTrack] = useState('');
  const [tree, setTree] = useState(null), [addCode, setAddCode] = useState('');
  const [validation, setValidation] = useState(null), [error, setError] = useState('');
  const [busy, setBusy] = useState(false), [dirty, setDirty] = useState(false);
  const [majors, setMajors] = useState([]), [majorId, setMajorId] = useState(''), [cohort, setCohort] = useState('');
  const [loading, setLoading] = useState(true), [detailLoading, setDetailLoading] = useState(false);
  const [sourceId, setSourceId] = useState(''), [sourceCode, setSourceCode] = useState(''), [source, setSource] = useState(null);
  const [addSemester, setAddSemester] = useState(1), [addGroup, setAddGroup] = useState(''), [revisionCode, setRevisionCode] = useState('');
  const [reloadKey, setReloadKey] = useState(0);
  useEffect(() => { onDirtyChange(dirty); }, [dirty, onDirtyChange]);
  useEffect(() => { if (initialId && initialId !== id) selectProgram(initialId); }, [initialId]);
  useEffect(() => { onProgramSelected(id); }, [id, onProgramSelected]);
  useEffect(() => {
    let live = true;
    setLoading(true);
    Promise.all([request('/api/curriculums', {token}), request('/api/courses', {token}), request('/api/majors', {token})])
      .then(([programs, courses, majors]) => { if (live) { setItems(programs ?? []); setCatalog(courses ?? []); setMajors(majors ?? []); } })
      .catch(error => { if (live) setError(error.message); }).finally(() => { if (live) setLoading(false); });
    return () => { live = false; };
  }, [token, request, reloadKey]);
  useEffect(() => {
    let live = true;
    setDetail(null); setTrack(''); setTree(null); setValidation(null); setError(''); setDirty(false); setDetailLoading(Boolean(id));
    if (id) request('/api/curriculums/' + id, {token}).then(program => {
      if (live) { setDetail(program); setTrack(program.specializations?.[0]?.code ?? ''); }
    }).catch(error => { if (live) setError(error.message); }).finally(() => { if (live) setDetailLoading(false); });
    return () => { live = false; };
  }, [id, token, request, reloadKey]);
  useEffect(() => {
    setDirty(false);
    if (!detail || !track) { setTree(null); return; }
    try { setTree(editableTrack(detail, track)); } catch (error) { setError(error.message); setTree(null); }
    setAddCode(''); setAddGroup(''); setRevisionCode('');
  }, [detail, track]);
  useEffect(() => {
    let live = true; setSource(null); setSourceCode('');
    if (sourceId) request('/api/curriculums/' + sourceId, {token}).then(value => {
      if (live) { setSource(value); setSourceCode(value.specializations?.[0]?.code ?? ''); }
    }).catch(error => { if (live) setError(error.message); });
    return () => { live = false; };
  }, [sourceId, token, request]);
  useEffect(() => {
    let live = true;
    if (detail && !dirty) request('/api/curriculums/' + detail.id + '/validation', {token})
      .then(value => { if (live) setValidation(value); }).catch(e => { if (live) setError(e.message); });
    return () => { live = false; };
  }, [detail, dirty, token, request]);
  const locked = detail?.status !== 'DRAFT';
  const rows = (tree?.semesters ?? []).flatMap(semester =>
    (semester.courses ?? []).map(course => ({...course, semesterNumber: semester.semesterNumber})))
    .sort((a,b) => a.semesterNumber - b.semesterNumber || String(courseRequirement(tree, a.courseCode)?.kind ?? '')
      .localeCompare(courseRequirement(tree, b.courseCode)?.kind ?? ''));
  const selectedCourses = new Set(rows.map(course => course.courseCode));
  const groupCatalog = catalog.filter(course => selectedCourses.has(course.courseCode));
  function change(value) { setTree(value); setDirty(true); onDirtyChange(true); setValidation(null); }
  function selectProgram(value) {
    if (value === id) return;
    setTree(null); setDetail(null); setValidation(null); setDirty(false); onDirtyChange(false); setId(value);
  }
  function saveRows(nextRows) {
    const semesters = new Map();
    for (const {semesterNumber, ...course} of nextRows) {
      if (!semesters.has(semesterNumber)) semesters.set(semesterNumber, []);
      semesters.get(semesterNumber).push(course);
    }
    const next = {...tree, semesters: [...semesters].sort((a, b) => a[0] - b[0])
      .map(([semesterNumber, courses]) => ({semesterNumber, courses}))};
    change(next); return next;
  }
  function updateCourse(index, key, value) {
    saveRows(rows.map((course, i) => i === index ? {...course, [key]: value} : course));
  }
  function updateGroup(index, key, value) {
    let next = {...tree, requirementGroups: tree.requirementGroups.map((group, i) =>
      i === index ? {...group, [key]: value} : group)};
    const group = next.requirementGroups[index];
    if (key === 'eligibleCourseCodes' || key === 'kind')
      for (const code of group.eligibleCourseCodes ?? []) next = assignTreeCourse(next, code, group.groupCode);
    change(next);
  }
  async function run(action) {
    setBusy(true); setError('');
    try {
      if (action === 'save') {
        if (locked || detail?.id !== id || !tree) throw new Error('Chờ tải đúng cây bản nháp trước khi lưu.');
        const saved = await request('/api/curriculums/' + id + '/specializations/' + encodeURIComponent(track) + '/tree',
          {token, method: 'PUT', body: tree});
        setDetail(saved); notify('Đã lưu cây chuyên ngành'); setDirty(false);
      } else if (action === 'validate') {
        setValidation(await request('/api/curriculums/' + id + '/validation', {token}));
      } else if (action === 'publish') {
        if (!canPublishTree(detail, dirty, validation)) throw new Error('Lưu cây và kiểm tra toàn bộ CTĐT trước khi công bố.');
        if (!window.confirm('Công bố toàn bộ CTĐT cùng tất cả cây chuyên ngành?')) return;
        const saved = await request('/api/curriculums/' + id + '/publish', {token, method: 'POST'});
        setDetail(saved); setItems(items.map(item => item.id === saved.id ? saved : item));
        notify('Đã công bố toàn bộ CTĐT');
      } else if (action === 'revision') {
        const saved = await request('/api/curriculums/' + id + '/clone', {token, method: 'POST', body: {
          newCurriculumCode: revisionCode.trim(), newCurriculumName: detail.curriculumName,
          newCohort: detail.cohort, newAppliedYear: detail.appliedYear}});
        setItems(current => [...current, saved]); selectProgram(saved.id); notify('Đã tạo phiên bản nháp mới; sinh viên giữ phiên bản đã gán');
      }
    } catch (error) { setError(error.message); } finally { setBusy(false); }
  }
  const filteredPrograms = items.filter(p => (!majorId || p.majorId === majorId) && (!cohort || p.cohort === cohort));
  const summary = validation?.creditSummariesBySpecialization?.[track];
  function switchFilter(setter, value) {
    if (confirmTreeSwitch(dirty, window.confirm)) { setter(value); selectProgram(''); }
  }
  return <section className="panel huf-feature-panel tree-workspace" aria-busy={busy || loading || detailLoading}><h3>Cây học phần theo chuyên ngành</h3>
    {loading && <p role="status">Đang tải CTĐT, ngành và danh mục học phần...</p>}
    <div className="form-grid">
      <label className="field-label">Ngành đào tạo<select value={majorId} disabled={busy || loading}
        onChange={e => switchFilter(value => { setMajorId(value); setCohort(''); }, e.target.value)}>
        <option value="">Tất cả ngành</option>{majors.map(m => <option key={m.id} value={m.id}>{m.name}</option>)}
      </select></label>
      <label className="field-label">Khóa tuyển sinh<select value={cohort} disabled={busy || loading}
        onChange={e => switchFilter(setCohort, e.target.value)}><option value="">Tất cả khóa</option>
        {[...new Set(items.filter(p => !majorId || p.majorId === majorId).map(p => p.cohort))].sort().map(c => <option key={c}>{c}</option>)}
      </select></label>
      <label className="field-label">CTĐT và phiên bản<select value={id} disabled={busy}
        onChange={event => { if (confirmTreeSwitch(dirty, window.confirm)) selectProgram(event.target.value); }}>
        <option value="">Chọn CTĐT</option>{filteredPrograms.map(item => <option key={item.id} value={item.id}>
          {item.curriculumCode} · {item.cohort} · Phiên bản {item.version} · {programStatus(item.status)}</option>)}
      </select></label>
      <label className="field-label">Chuyên ngành<select value={track} disabled={busy}
        onChange={event => { if (confirmTreeSwitch(dirty, window.confirm)) {
          setTree(null); setDirty(false); onDirtyChange(false); setTrack(event.target.value);
        } }}>
        <option value="">Chọn chuyên ngành</option>
        {(detail?.specializations ?? []).map(spec => <option key={spec.code} value={spec.code}>{spec.name} ({spec.code})</option>)}
      </select></label>
    </div>
    {!loading && !filteredPrograms.length && <p role="status">Chưa có CTĐT phù hợp. Tạo bản nháp tại tab Thông tin và phiên bản.</p>}
    {detailLoading && <p role="status">Đang tải cây học phần...</p>}
    {majorId && <p>Chuyên ngành của ngành đã chọn: {(majors.find(m => m.id === majorId)?.specializations ?? [])
      .filter(s => s.status !== 'INACTIVE').map(s => s.name).join(' · ') || 'Chưa có chuyên ngành trong danh mục ngành'}.</p>}
    {detail && !detail.specializations?.length && <p>Thêm chuyên ngành từ danh mục ngành tại tab Thông tin và phiên bản, trong phần Chi tiết CTĐT.</p>}
    {tree && detail?.id === id && <>
      <p>{locked ? 'Phiên bản đã khóa. Tạo bản sao để thay đổi.' : 'Lưu bản nháp trước khi kiểm tra và công bố.'}</p>
      <div className="tree-metrics"><span>Bắt buộc <b>{summary?.mandatoryCredits ?? 'Chưa kiểm tra'}</b></span>
        <span>Chỉ tiêu tự chọn <b>{summary?.electiveCredits ?? 'Chưa kiểm tra'}</b></span>
        <span>Phương án tốt nghiệp <b>{summary?.graduationCredits ?? 'Chưa kiểm tra'}</b></span>
        <span>Tổng yêu cầu <b>{summary?.requiredTotal ?? 'Chưa kiểm tra'}</b></span></div>
      {treeWarnings(tree).length > 0 && <div className="huf-help-box" role="status"><b>Cấu hình cần kiểm tra</b>
        {treeWarnings(tree).map((message, i) => <p key={i}>{detail.specializations.find(s => s.code === track)?.name} · {message}</p>)}</div>}
      {locked ? <div className="huf-inline-actions"><Field label="Mã CTĐT cho phiên bản mới" value={revisionCode} set={setRevisionCode}/>
        <button className="outline" disabled={busy || !revisionCode.trim()} onClick={() => run('revision')}>Tạo phiên bản nháp mới</button></div>
        : <details className="tree-copy"><summary>Sao chép cây từ CTĐT hoặc chuyên ngành khác</summary>
          <div className="form-grid"><label className="field-label">CTĐT nguồn<select value={sourceId} onChange={e => { setSource(null); setSourceCode(''); setSourceId(e.target.value); }}>
            <option value="">Chọn CTĐT nguồn</option>{items.map(p => <option key={p.id} value={p.id}>{p.curriculumName} · {p.cohort} · Phiên bản {p.version}</option>)}
          </select></label><label className="field-label">Chuyên ngành nguồn<select value={sourceCode} onChange={e => setSourceCode(e.target.value)}>
            {(source?.specializations ?? []).map(s => <option key={s.code} value={s.code}>{s.name}</option>)}
          </select></label></div><button className="outline" disabled={busy || !source || !sourceCode} onClick={() => {
            if (!window.confirm('Thay cấu hình cây đang chọn bằng bản sao? Chỉ lưu vào bản nháp khi bạn nhấn Lưu.')) return;
            try { change(copyTrack(source, sourceCode, detail, track)); notify('Đã sao chép vào vùng soạn thảo; cần lưu và kiểm tra lại'); }
            catch (e) { setError(e.message); }
          }}>Sao chép vào cây đang chọn</button></details>}
      <fieldset disabled={locked || busy}><h4>Học phần và học kỳ</h4>
        {!locked && <div className="huf-inline-actions">
          <select aria-label="Thêm học phần từ danh mục" value={addCode} onChange={event => setAddCode(event.target.value)}>
            <option value="">Chọn học phần từ danh mục</option>
            {catalog.filter(course => !selectedCourses.has(course.courseCode)).map(course =>
              <option key={course.courseCode} value={course.courseCode}>{course.courseCode} · {course.courseName}</option>)}
          </select><button className="outline" disabled={!addCode} onClick={() => {
            const course = catalog.find(item => item.courseCode === addCode);
            const next = saveRows([...rows, {courseCode: course.courseCode, courseName: course.courseName, credits: course.credits,
              type: 'MANDATORY', semesterNumber: Number(addSemester), specializationCodes: [], prerequisiteCourseCodes: []}]);
            if (addGroup) change(assignTreeCourse(next, course.courseCode, addGroup)); setAddCode('');
          }}>Thêm học phần</button>
          <Field label="Học kỳ dự kiến" type="number" min="1" value={addSemester} set={setAddSemester}/>
          <label className="field-label">Nhóm yêu cầu<select value={addGroup} onChange={e => setAddGroup(e.target.value)}>
            <option value="">Phân nhóm sau</option>{(tree.requirementGroups ?? []).map(g => <option key={g.groupCode} value={g.groupCode}>{g.groupName || kinds[g.kind]}</option>)}
          </select></label>
        </div>}
        {!rows.length && <p>Cây chưa có học phần. Thêm học phần từ danh mục chung hoặc sao chép cây.</p>}
        {rows.map((course, index) => <div className="huf-group-editor" key={course.courseCode + '-' + index}>
          <h4>Học kỳ {course.semesterNumber} · {kinds[courseRequirement(tree, course.courseCode)?.kind] || 'Chưa phân nhóm yêu cầu'}</h4>
          <b>{course.courseCode} · {course.courseName} ({course.credits} tín chỉ)</b><div className="form-grid">
            <Field label="Học kỳ" type="number" min="1" value={course.semesterNumber}
              set={value => updateCourse(index, 'semesterNumber', Number(value))}/>
            <label className="field-label">Nhóm yêu cầu<select value={courseRequirement(tree, course.courseCode)?.groupCode ?? ''}
              onChange={event => change(assignTreeCourse(tree, course.courseCode, event.target.value))}>
              <option value="">Chưa phân nhóm</option>{(tree.requirementGroups ?? []).map(g => <option key={g.groupCode} value={g.groupCode}>{g.groupName || kinds[g.kind]}</option>)}
            </select></label>
            <CourseSelect label="Tiên quyết (giữ Ctrl để chọn nhiều)" items={groupCatalog.filter(item => item.courseCode !== course.courseCode)}
              codes={course.prerequisiteCourseCodes} change={codes => updateCourse(index, 'prerequisiteCourseCodes', codes)}/>
          </div>{!locked && <button className="outline" onClick={() => change(removeTreeCourse(tree, course.courseCode))}>Bỏ học phần khỏi cây</button>}
        </div>)}
        <h4>Nhóm tín chỉ và phương án tốt nghiệp</h4>
        <p>Mỗi cây có một nhóm tự chọn chung. Hệ thống xét môn đạt theo ngày đạt đầu tiên,
          rồi theo mã học phần, đến khi đủ chỉ tiêu. Sinh viên không cần đăng ký môn tự chọn.</p>
        {(tree.requirementGroups ?? []).map((group, index) => <div className="huf-group-editor" key={index}>
          <div className="form-grid">
            <Field label="Mã nhóm" value={group.groupCode ?? ''} set={value => updateGroup(index, 'groupCode', value)}/>
            <Field label="Tên nhóm" value={group.groupName ?? ''} set={value => updateGroup(index, 'groupName', value)}/>
            <label className="field-label">Loại nhóm<select value={group.kind}
              onChange={event => updateGroup(index, 'kind', event.target.value)}>
              {Object.entries(kinds).filter(([code]) => !code.startsWith('ELECTIVE')
                || code === (group.kind === 'ELECTIVE_SPECIALIZATION' ? 'ELECTIVE_SPECIALIZATION' : 'ELECTIVE_FREE'))
                .map(([code, name]) => <option key={code} value={code}
                disabled={code.startsWith('ELECTIVE') && (tree.requirementGroups ?? []).some((other, i) =>
                  i !== index && other.kind?.startsWith('ELECTIVE'))}>{name}</option>)}
            </select></label>
            <Field label="Tín chỉ yêu cầu" type="number" min="0" value={group.requiredCredits ?? 0}
              set={value => updateGroup(index, 'requiredCredits', Number(value))}/>
          </div><CourseSelect label="Học phần của nhóm (giữ Ctrl để chọn nhiều)" items={groupCatalog}
            codes={group.eligibleCourseCodes} change={codes => updateGroup(index, 'eligibleCourseCodes', codes)}/>
          {group.kind === 'GRADUATION' && <>
            {(group.completionOptions ?? []).map((option, optionIndex) => <div key={optionIndex} className="huf-group-editor">
              <Field label="Mã phương án" value={option.optionCode} set={value => updateGroup(index, 'completionOptions',
                group.completionOptions.map((item, i) => i === optionIndex ? {...item, optionCode: value} : item))}/>
              <CourseSelect label="Môn của phương án" items={groupCatalog} codes={option.courseCodes}
                change={codes => updateGroup(index, 'completionOptions', group.completionOptions.map((item, i) =>
                  i === optionIndex ? {...item, courseCodes: codes} : item))}/>
              {!locked && <button className="outline" onClick={() => updateGroup(index, 'completionOptions',
                group.completionOptions.filter((_, i) => i !== optionIndex))}>Xóa phương án</button>}
            </div>)}
            {!locked && <button className="outline" onClick={() => updateGroup(index, 'completionOptions',
              [...(group.completionOptions ?? []), {optionCode: '', courseCodes: []}])}>Thêm phương án</button>}
          </>}
          <div className="huf-inline-actions">
            <label><input type="checkbox" checked={group.countsTowardGpa !== false}
              onChange={event => updateGroup(index, 'countsTowardGpa', event.target.checked)}/> Tính GPA</label>
            <label><input type="checkbox" checked={group.countsTowardGraduation !== false}
              onChange={event => updateGroup(index, 'countsTowardGraduation', event.target.checked)}/> Tính tín chỉ tốt nghiệp</label>
            {!locked && <button className="outline" onClick={() => change({...tree,
              requirementGroups: tree.requirementGroups.filter((_, i) => i !== index)})}>Xóa nhóm</button>}
          </div>
        </div>)}
        {!locked && <div className="huf-inline-actions">
          <button className="outline" onClick={() => change({...tree, requirementGroups: [...(tree.requirementGroups ?? []),
            {groupCode: '', groupName: '', kind: 'MANDATORY_COMMON', specializationCodes: [track],
              requiredCredits: 0, eligibleCourseCodes: [], completionOptions: []}]})}>Thêm nhóm</button>
          <button className="primary" onClick={() => run('save')}>Lưu cây bản nháp</button>
        </div>}
      </fieldset>
      <div className="huf-inline-actions">
        <button className="outline" disabled={busy || dirty} onClick={() => run('validate')}>Kiểm tra toàn bộ cây</button>
        {!locked && <button className="primary" disabled={busy || !canPublishTree(detail, dirty, validation)}
          onClick={() => run('publish')}>Công bố toàn bộ CTĐT</button>}
      </div>
      {validation && <div role="status"><p>{validation.valid ? 'Tất cả cây hợp lệ.' : 'CTĐT chưa đủ điều kiện công bố.'}</p>
        {validation.errors?.map((message, index) => <p className="form-error" key={index}>{treeValidationMessage(message, detail)}</p>)}
        {Object.entries(validation.computedCreditsBySpecialization ?? {}).map(([code, credits]) =>
          <p key={code}>{detail.specializations.find(s => s.code === code)?.name ?? 'Chuyên ngành'} — {credits} tín chỉ yêu cầu</p>)}
      </div>}
    </>}
    {error && <div role="alert"><p className="form-error">{treeValidationMessage(error, detail)}</p>
      <button className="outline" disabled={busy} onClick={() => {
        if (confirmTreeSwitch(dirty, window.confirm)) setReloadKey(value => value + 1);
      }}>Tải lại dữ liệu</button></div>}
  </section>;
}
