import React, {useEffect, useRef, useState} from 'react';
import CoursePicker from './CoursePicker.jsx';
import GraduationOptions from './GraduationOptions.jsx';
import {editableTrack, copyTrack, confirmTreeSwitch, canPublishTree, programStatus, requirementNames as kinds,
  courseRequirement, assignTreeCourse, removeTreeCourse, treeValidationMessage} from './curriculum-ui.js';
import {emptyTree, treeCourses, addSemester, addCourses, editCourse, deriveMandatory, prerequisiteReason,
  builderWarnings, validationLocation, groupCredits} from './curriculum-builder.js';
import './curriculum-builder.css';

const steps = ['Thông tin CTĐT', 'Cây học phần', 'Yêu cầu tín chỉ', 'Kiểm tra và công bố'];
const blank = () => ({curriculumCode: '', curriculumName: '', majorId: '', cohort: '', appliedYear: new Date().getFullYear(),
  description: '', status: 'DRAFT', specializations: []});
function Input({label, value, change, ...props}) {
  return <label className="field-label">{label}<input value={value ?? ''} onChange={e => change?.(e.target.value)} {...props}/></label>;
}
function BuilderDialog({label, close, children}) {
  const ref = useRef(null);
  useEffect(() => {
    const previous = document.activeElement;
    ref.current?.querySelector('button, input, select')?.focus();
    return () => previous?.focus();
  }, []);
  return <div className="modal-backdrop"><section ref={ref} className="modal cb-dialog" role="dialog" aria-modal="true" aria-label={label}
    onKeyDown={e => {
      if (e.key === 'Escape') {e.preventDefault(); close();}
      if (e.key !== 'Tab') return;
      const controls = [...ref.current.querySelectorAll('button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled)')]
        .filter(element => !element.closest('fieldset:disabled') && element.getClientRects().length);
      const first = controls[0], last = controls.at(-1);
      if (e.shiftKey && document.activeElement === first) {e.preventDefault(); last?.focus();}
      if (!e.shiftKey && document.activeElement === last) {e.preventDefault(); first?.focus();}
    }}>{children}</section></div>;
}
export default function CurriculumTreeWorkspace({token, request, notify = () => {}, initialId = '', onDirtyChange = () => {}, onProgramSelected = () => {}}) {
  const [items, setItems] = useState([]), [catalog, setCatalog] = useState([]), [majors, setMajors] = useState([]);
  const [id, setId] = useState(initialId), [detail, setDetail] = useState(null), [meta, setMeta] = useState(blank);
  const [trees, setTrees] = useState({}), [changed, setChanged] = useState([]), [metaDirty, setMetaDirty] = useState(false);
  const [track, setTrack] = useState(''), [step, setStep] = useState(1), [validation, setValidation] = useState(null);
  const [busy, setBusy] = useState(false), [loading, setLoading] = useState(true), [detailLoading, setDetailLoading] = useState(false);
  const [error, setError] = useState(''), [majorFilter, setMajorFilter] = useState(''), [cohortFilter, setCohortFilter] = useState('');
  const [term, setTerm] = useState(1), [adding, setAdding] = useState(null), [groupCode, setGroupCode] = useState('');
  const [editing, setEditing] = useState(''), [revisionCode, setRevisionCode] = useState('');
  const [sourceId, setSourceId] = useState(''), [sourceCode, setSourceCode] = useState(''), [source, setSource] = useState(null);
  const [reload, setReload] = useState(0);
  const dirty = metaDirty || changed.length > 0, locked = Boolean(detail && detail.status !== 'DRAFT');
  const tree = trees[track], rows = treeCourses(tree), selected = new Set(rows.map(c => c.courseCode));
  const currentCourse = rows.find(c => c.courseCode === editing);
  const selectedMajor = majors.find(m => m.id === meta.majorId);
  const existingProgram = !detail && items.find(p => p.majorId === meta.majorId && p.cohort === meta.cohort.trim());
  useEffect(() => {onDirtyChange(dirty);}, [dirty, onDirtyChange]);
  useEffect(() => {
    const warn = event => {if (dirty) {event.preventDefault(); event.returnValue = '';}};
    window.addEventListener('beforeunload', warn); return () => window.removeEventListener('beforeunload', warn);
  }, [dirty]);
  useEffect(() => {if (initialId && initialId !== id) selectProgram(initialId);}, [initialId]);
  useEffect(() => {onProgramSelected(id);}, [id, onProgramSelected]);
  useEffect(() => {
    let live = true;
    Promise.all([request('/api/curriculums', {token}), request('/api/courses', {token}), request('/api/majors', {token})])
      .then(([programs, courses, data]) => {if (live) {setItems(programs ?? []); setCatalog(courses ?? []); setMajors(data ?? []);}})
      .catch(e => {if (live) setError(e.message);}).finally(() => {if (live) setLoading(false);});
    return () => {live = false;};
  }, [token, request, reload]);
  function install(program) {
    const buffers = {};
    for (const spec of program.specializations ?? []) buffers[spec.code] = editableTrack(program, spec.code);
    setDetail(program); setMeta({...program}); setTrees(buffers); setTrack(program.specializations?.[0]?.code ?? '');
    setChanged([]); setMetaDirty(false); setValidation(null); setEditing(''); setAdding(null);
  }
  useEffect(() => {
    let live = true;
    setError(''); setValidation(null); setDetail(null); setTrees({}); setTrack(''); setChanged([]); setMetaDirty(false);
    if (id === 'new') {setMeta(blank()); setStep(0); setDetailLoading(false);}
    else if (id) {
      setDetailLoading(true);
      request('/api/curriculums/' + id, {token}).then(p => {if (live) {install(p); setStep(1);}})
        .catch(e => {if (live) setError(e.message);}).finally(() => {if (live) setDetailLoading(false);});
    }
    return () => {live = false;};
  }, [id, token, request, reload]);
  useEffect(() => {
    let live = true; setSource(null); setSourceCode('');
    if (sourceId) request('/api/curriculums/' + sourceId, {token}).then(p => {if (live) {setSource(p); setSourceCode(p.specializations?.[0]?.code ?? '');}})
      .catch(e => {if (live) setError(e.message);});
    return () => {live = false;};
  }, [sourceId, token, request]);
  function selectProgram(value) {
    if (value === id) return;
    if (!confirmTreeSwitch(dirty, window.confirm)) return;
    setId(value); setEditing(''); setAdding(null); setRevisionCode('');
  }
  function change(next) {
    if (locked || busy) return;
    setTrees(old => ({...old, [track]: next})); setChanged(old => [...new Set([...old, track])]); setValidation(null);
  }
  function metadata(key, value) {setMeta(old => ({...old, [key]: value})); setMetaDirty(true); setValidation(null); setError('');}
  function chooseSpec(spec, checked) {
    if (!checked && (detail?.specializations?.some(s => s.code === spec.code) || treeCourses(trees[spec.code]).length)
      && !window.confirm('Bỏ chuyên ngành ' + spec.name + ' và cây tương ứng khỏi bản nháp khi Lưu? Danh mục học phần dùng chung vẫn được giữ nguyên.')) return;
    metadata('specializations', checked ? [...meta.specializations, {code: spec.code, name: spec.name}] : meta.specializations.filter(s => s.code !== spec.code));
    if (checked) {
      if (!trees[spec.code]) setTrees(old => ({...old, [spec.code]: emptyTree(spec.code)}));
      setChanged(old => [...new Set([...old, spec.code])]);
    }
    if (checked && !track) setTrack(spec.code);
    if (!checked && track === spec.code) setTrack(meta.specializations.find(s => s.code !== spec.code)?.code ?? '');
  }
  function updateGroup(index, patch) {
    change(deriveMandatory({...tree, requirementGroups: tree.requirementGroups.map((g,i) => i === index ? {...g,...patch} : g)}, catalog));
  }
  async function save() {
    if (locked || busy) return;
    setBusy(true); setError('');
    let saved = detail, writes = 0;
    try {
      if (!meta.curriculumName.trim() || !meta.majorId || !meta.cohort.trim() || !Number.isInteger(Number(meta.appliedYear)))
        throw new Error('Điền tên CTĐT, ngành, khóa tuyển sinh và năm áp dụng hợp lệ ở bước 1.');
      const allowed = new Set((selectedMajor?.specializations ?? []).map(s => s.code));
      if (meta.specializations.some(s => !allowed.has(s.code))) throw new Error('Chuyên ngành không thuộc ngành đào tạo đã chọn.');
      if (!saved && !meta.curriculumCode.trim()) throw new Error('Nhập mã CTĐT ở bước 1.');
      if (existingProgram) {setStep(0); throw new Error('Ngành và khóa đã có CTĐT. Mở phiên bản hiện có và tạo bản sao để thay đổi.');}
      // The existing delete API removes the matching draft tree too. A metadata-only
      // PUT would leave an orphan tree that can never pass whole-program validation.
      for (const removed of (saved?.specializations ?? []).filter(s => !meta.specializations.some(item => item.code === s.code))) {
        saved = await request('/api/curriculums/' + saved.id + '/specializations/' + encodeURIComponent(removed.code), {token, method: 'DELETE'});
        writes++; setDetail(saved);
      }
      if (!saved || metaDirty) {
        const body = {curriculumName: meta.curriculumName.trim(), majorId: meta.majorId, cohort: meta.cohort.trim(),
          appliedYear: Number(meta.appliedYear), description: meta.description ?? '', specializations: meta.specializations,
          specializationSelectionTiming: 'EARLY', specializationSelectionSemester: 1};
        if (!saved) Object.assign(body, {curriculumCode: meta.curriculumCode.trim(), status: 'DRAFT', semesters: [], knowledgeGroups: []});
        saved = await request('/api/curriculums' + (saved ? '/' + saved.id : ''), {token, method: saved ? 'PUT' : 'POST', body});
        writes++; setItems(old => [...old.filter(p => p.id !== saved.id), saved]);
        setDetail(saved); setMeta(old => ({...old, id: saved.id, version: saved.version})); setMetaDirty(false);
      }
      // Keep each successful tree acknowledged, and retain other local trees on a partial failure.
      for (const code of changed.filter(c => meta.specializations.some(s => s.code === c))) {
        saved = await request('/api/curriculums/' + saved.id + '/specializations/' + encodeURIComponent(code) + '/tree',
          {token, method: 'PUT', body: trees[code]});
        writes++;
        setDetail(saved); setChanged(old => old.filter(c => c !== code));
      }
      setChanged(old => old.filter(c => meta.specializations.some(s => s.code === c)));
      setItems(old => [...old.filter(p => p.id !== saved.id), saved]); setValidation(null);
      // A newly created draft already has local buffers; do not reload and discard them.
      if (id === 'new') setId(saved.id);
      notify('Đã lưu bản nháp CTĐT và các cây chuyên ngành');
    } catch (e) {setError((writes ? 'Đã lưu một phần. Các thay đổi còn lại vẫn được giữ; nhấn Lưu bản nháp để thử lại. ' : '') + e.message);} finally {setBusy(false);}
  }
  async function run(action) {
    setBusy(true); setError('');
    try {
      if (action === 'validate') {setStep(3); setValidation(await request('/api/curriculums/' + detail.id + '/validation', {token}));}
      if (action === 'publish') {
        if (!canPublishTree(detail, dirty, validation)) throw new Error('Lưu và kiểm tra toàn bộ CTĐT trước khi công bố.');
        if (!window.confirm('Công bố toàn bộ CTĐT cùng tất cả cây chuyên ngành?')) return;
        const p = await request('/api/curriculums/' + detail.id + '/publish', {token, method: 'POST'});
        install(p); setStep(3); setItems(old => old.map(item => item.id === p.id ? p : item)); notify('Đã công bố toàn bộ CTĐT');
      }
      if (action === 'revision') {
        const p = await request('/api/curriculums/' + detail.id + '/clone', {token, method: 'POST', body: {
          newCurriculumCode: revisionCode.trim(), newCurriculumName: meta.curriculumName, newCohort: meta.cohort, newAppliedYear: Number(meta.appliedYear)}});
        setItems(old => [...old,p]); selectProgram(p.id); notify('Đã tạo phiên bản nháp mới; sinh viên giữ phiên bản đã gán');
      }
    } catch (e) {setError(e.message);} finally {setBusy(false);}
  }
  const visible = items.filter(p => (!majorFilter || p.majorId === majorFilter) && (!cohortFilter || p.cohort === cohortFilter));
  const copySource = sourceId === detail?.id ? {...detail, specializationTrees: trees} : source;
  const active = id === 'new' || Boolean(detail);
  const warnings = tree ? builderWarnings(tree) : [];
  return <section className="panel huf-feature-panel tree-workspace cb-workspace" aria-busy={busy || loading || detailLoading}>
    <div className="cb-heading"><div><h3>Tạo và quản lý CTĐT</h3><p>Quản lý cây học phần theo chuyên ngành.</p></div>
      <button className="primary" disabled={busy || loading} onClick={() => {
        if (id === 'new') {if (confirmTreeSwitch(dirty, window.confirm)) {setDetail(null);setMeta(blank()); setTrees({}); setChanged([]); setMetaDirty(false); setTrack(''); setStep(0);}}
        else selectProgram('new');
      }}>Tạo CTĐT mới</button></div>
    {loading && <p role="status">Đang tải CTĐT, ngành và danh mục học phần...</p>}
    <div className="cb-program-filters">
      <label className="field-label">Ngành đào tạo<select disabled={busy || loading} value={majorFilter} onChange={e => {
        if (confirmTreeSwitch(dirty, window.confirm)) {setMajorFilter(e.target.value); setCohortFilter(''); setId('');}
      }}><option value="">Tất cả ngành</option>{majors.map(m => <option key={m.id} value={m.id}>{m.name}</option>)}</select></label>
      <label className="field-label">Khóa tuyển sinh<select disabled={busy || loading} value={cohortFilter} onChange={e => {
        if (confirmTreeSwitch(dirty, window.confirm)) {setCohortFilter(e.target.value); setId('');}
      }}><option value="">Tất cả khóa</option>{[...new Set(items.filter(p => !majorFilter || p.majorId === majorFilter).map(p => p.cohort))].sort().map(c => <option key={c}>{c}</option>)}</select></label>
      <label className="field-label">CTĐT và phiên bản<select value={detail?.id ?? id} disabled={busy} onChange={e => selectProgram(e.target.value)}>
        <option value="">Chọn CTĐT</option>{id === 'new' && !detail && <option value="new">CTĐT mới chưa lưu</option>}
        {visible.map(p => <option key={p.id} value={p.id}>{p.curriculumCode} · {p.cohort} · Phiên bản {p.version} · {programStatus(p.status)}</option>)}</select></label>
    </div>
    {detailLoading && <p role="status">Đang tải cây học phần...</p>}
    {!active && !loading && <div className="cb-empty">Chọn phiên bản để tiếp tục, hoặc tạo CTĐT mới từ danh mục ngành và học phần chung.</div>}
    {active && !detailLoading && <>
      <nav className="cb-steps" aria-label="Các bước xây dựng CTĐT">{steps.map((label,index) => <button key={label} type="button"
        aria-current={step === index ? 'step' : undefined} className={step === index ? 'primary' : 'outline'} onClick={() => setStep(index)}>{index + 1}. {label}</button>)}</nav>
      <div className="cb-draft-status" role="status">{locked ? 'Phiên bản đã khóa. Tạo bản sao để thay đổi.' : 'Lưu bản nháp trước khi kiểm tra và công bố.'}
        {dirty && <b> Có thay đổi chưa lưu. Chuyển chuyên ngành vẫn giữ nội dung đang soạn.</b>}</div>
      {detail?.clonedFromCurriculumId && <p className="huf-help-box">Nguồn sao chép: {items.find(p => p.id === detail.clonedFromCurriculumId)?.curriculumName ?? 'Phiên bản CTĐT trước'} · {items.find(p => p.id === detail.clonedFromCurriculumId)?.curriculumCode ?? 'Xem lịch sử phiên bản'}.</p>}
      {locked && <div className="cb-revision"><Input label="Mã CTĐT cho phiên bản mới" value={revisionCode} change={setRevisionCode}/>
        <button className="outline" disabled={busy || !revisionCode.trim()} onClick={() => run('revision')}>Tạo phiên bản nháp mới</button></div>}
      {step === 0 && <fieldset className="cb-section" disabled={locked || busy}><legend>1. Thông tin CTĐT</legend><div className="form-grid">
        <Input label="Mã CTĐT" value={meta.curriculumCode} readOnly={Boolean(detail)} change={v => metadata('curriculumCode',v.toUpperCase())}/>
        <Input label="Tên CTĐT" value={meta.curriculumName} change={v => metadata('curriculumName',v)}/>
        <label className="field-label">Ngành của CTĐT<select value={meta.majorId} onChange={e => {
          const next = e.target.value, allowed = new Set((majors.find(m => m.id === next)?.specializations ?? []).map(s => s.code));
          if (meta.specializations.some(s => !allowed.has(s.code))) {setError('Bỏ chọn các chuyên ngành không thuộc ngành mới trước khi đổi ngành.'); return;}
          metadata('majorId', next);
        }}><option value="">Chọn ngành đào tạo</option>{majors.map(m => <option key={m.id} value={m.id}>{m.name}</option>)}</select></label>
        <Input label="Khóa tuyển sinh của CTĐT" value={meta.cohort} change={v => metadata('cohort',v)}/>
        <Input label="Năm áp dụng" type="number" value={meta.appliedYear} change={v => metadata('appliedYear',v)}/>
        <Input label="Phiên bản" readOnly value={detail?.version ?? 'Hệ thống cấp khi lưu bản nháp'}/>
      </div><fieldset className="cb-specs"><legend>Chuyên ngành thuộc ngành đã chọn</legend>
        {!selectedMajor && <p>Chọn ngành để xem các chuyên ngành.</p>}
        {selectedMajor && !selectedMajor.specializations?.length && <p>Ngành chưa có chuyên ngành trong danh mục.</p>}
        {(selectedMajor?.specializations ?? []).map(s => <label key={s.code}><input type="checkbox" checked={meta.specializations.some(spec => spec.code === s.code)}
          disabled={s.status === 'INACTIVE' && !meta.specializations.some(spec => spec.code === s.code)} onChange={e => chooseSpec(s,e.target.checked)}/>{s.name}{s.status === 'INACTIVE' && ' (Ngừng áp dụng)'}</label>)}
      </fieldset><label className="field-label">Mô tả<textarea value={meta.description ?? ''} onChange={e => metadata('description',e.target.value)}/></label>
      {existingProgram && <div className="cb-warnings" role="status"><p>Ngành và khóa đã có {existingProgram.curriculumName} · Phiên bản {existingProgram.version}. Hãy sao chép để tạo phiên bản mới.</p>
        <button className="outline" onClick={()=>selectProgram(existingProgram.id)}>Mở CTĐT đã có</button></div>}
      <p className="field-hint">Có thể lưu bản nháp chưa hoàn chỉnh.</p></fieldset>}
      {(step === 1 || step === 2) && <>
        <label className="field-label cb-track-select">Chuyên ngành<select value={track} disabled={busy} onChange={e => {setTrack(e.target.value); setEditing(''); setAdding(null);}}>
          <option value="">Chọn chuyên ngành</option>{meta.specializations.map(s => <option key={s.code} value={s.code}>{s.name} ({s.code}){changed.includes(s.code) ? ' · Chưa lưu' : ''}</option>)}</select></label>
        {!tree && <div className="cb-empty">Chọn chuyên ngành ở bước 1 để xây dựng cây học phần.</div>}
        {tree && <>
          {warnings.length > 0 && <details className="cb-warnings"><summary>{warnings.length} nội dung cần kiểm tra trong cây</summary>{warnings.map((w,i) => <p key={i}>{w}</p>)}</details>}
          {step === 1 && <>
            {!locked && <details className="tree-copy"><summary>Sao chép cây từ CTĐT hoặc chuyên ngành khác</summary><div className="form-grid">
              <label className="field-label">CTĐT nguồn<select value={sourceId} onChange={e => setSourceId(e.target.value)}><option value="">Chọn CTĐT nguồn</option>
                {items.map(p => <option key={p.id} value={p.id}>{p.curriculumName} · {p.cohort} · Phiên bản {p.version}</option>)}</select></label>
              <label className="field-label">Chuyên ngành nguồn<select value={sourceCode} onChange={e => setSourceCode(e.target.value)}>{(copySource?.specializations ?? []).map(s => <option key={s.code} value={s.code}>{s.name}</option>)}</select></label>
            </div><button className="outline" disabled={busy || !copySource || !sourceCode} onClick={() => {
              if (!window.confirm('Thay cây đang chọn bằng bản sao? Nội dung chỉ được ghi khi lưu bản nháp.')) return;
              try {change(copyTrack(copySource,sourceCode,{...meta,status:'DRAFT'},track)); notify('Đã sao chép vào vùng soạn thảo; cần lưu và kiểm tra lại');} catch(e) {setError(e.message);}
            }}>Sao chép vào cây đang chọn</button></details>}
            <div className="cb-term-nav" aria-label="Đi đến học kỳ">{tree.semesters.map(s => <a key={s.semesterNumber} href={'#cb-term-' + s.semesterNumber}>Kỳ {s.semesterNumber}</a>)}</div>
            {!locked && <div className="cb-inline"><Input label="Học kỳ mới" type="number" min="1" step="1" value={term} change={setTerm}/><button className="outline" disabled={busy} onClick={() => {
              try {change(addSemester(tree,Number(term)));} catch(e) {setError(e.message);}
            }}>Thêm học kỳ</button></div>}
            {!tree.semesters.length && <p>Chưa có học kỳ. Thêm học kỳ hoặc sao chép một cây có sẵn.</p>}
            {[...tree.semesters].sort((a,b)=>a.semesterNumber-b.semesterNumber).map(s => <details className="cb-semester" id={'cb-term-' + s.semesterNumber} key={track + s.semesterNumber} open={s.semesterNumber === 1}>
              <summary><b>Học kỳ {s.semesterNumber}</b><span>{s.courses?.length ?? 0} học phần · {(s.courses ?? []).reduce((sum,c)=>sum+(catalog.find(item=>item.courseCode===c.courseCode)?.credits ?? c.credits ?? 0),0)} tín chỉ</span>
                <small>{[...new Set((s.courses ?? []).map(c=>kinds[courseRequirement(tree,c.courseCode)?.kind] ?? 'Chưa phân nhóm'))].join(' · ')}</small></summary>
              {(s.courses ?? []).map(c => <article className="cb-course" key={c.courseCode}><div><b>{c.courseCode}</b><span>{catalog.find(item=>item.courseCode===c.courseCode)?.courseName ?? c.courseName}</span>
                <small>{c.credits ?? '—'} tín chỉ · {kinds[courseRequirement(tree,c.courseCode)?.kind] ?? 'Chưa phân nhóm'} · Tiên quyết: {c.prerequisiteCourseCodes?.join(', ') || 'Không có'}</small></div>
                <button className="outline" disabled={busy} onClick={()=>{setError('');setEditing(c.courseCode);}} aria-label={'Chi tiết ' + c.courseCode}>{locked ? 'Chi tiết' : 'Chỉnh sửa'}</button></article>)}
              {!s.courses?.length && <p>Học kỳ chưa có học phần.</p>}
              {!locked && <button className="outline" disabled={busy} onClick={()=>{setAdding(s.semesterNumber); setGroupCode(tree.requirementGroups?.[0]?.groupCode ?? '');}}>Thêm học phần vào kỳ {s.semesterNumber}</button>}
            </details>)}
          </>}
          {step === 2 && <fieldset className="cb-section" disabled={locked || busy}><legend>3. Yêu cầu tín chỉ của cây</legend>
            <p>Mỗi cây có một nhóm tự chọn chung, hoàn thành khi đủ chỉ tiêu.</p>
            {(tree.requirementGroups ?? []).map((g,index) => <section className="cb-group" key={index}><h4>{kinds[g.kind] ?? 'Nhóm yêu cầu'} · {g.groupName || g.groupCode}</h4><div className="form-grid">
              <Input label="Tên nhóm" value={g.groupName} change={v=>updateGroup(index,{groupName:v})}/>
              {g.kind?.startsWith('MANDATORY') ? <p className="cb-derived">{groupCredits(tree,g,catalog)} tín chỉ theo danh mục học phần.</p> :
                <Input label={g.kind?.startsWith('ELECTIVE') ? 'Chỉ tiêu tín chỉ tự chọn' : 'Tín chỉ yêu cầu của nhóm'} type="number" min="0" step="1" value={g.requiredCredits ?? 0} change={v=>updateGroup(index,{requiredCredits:Number(v)})}/>}
            </div>
              {g.kind !== 'GRADUATION' && <p>{(g.eligibleCourseCodes ?? []).join(' · ') || 'Chưa có học phần. Phân nhóm ở bước Cây học phần.'}</p>}
              {g.kind === 'GRADUATION' && <GraduationOptions key={track+'-'+g.groupCode} group={g} tree={tree} catalog={catalog} readOnly={locked || busy} onChange={value=>updateGroup(index,value)}/>}
              <details><summary>Thiết lập nâng cao</summary><p>Chỉ thay đổi khi cần điều chỉnh yêu cầu của nhóm.</p><div className="cb-inline">
                <label><input type="checkbox" checked={g.countsTowardGpa !== false} onChange={e=>updateGroup(index,{countsTowardGpa:e.target.checked})}/> Tính GPA</label>
                <label><input type="checkbox" checked={g.countsTowardGraduation !== false} onChange={e=>updateGroup(index,{countsTowardGraduation:e.target.checked})}/> Tính tín chỉ tốt nghiệp</label>
              </div><button className="outline" onClick={()=>{if(window.confirm('Xóa nhóm và bỏ phân nhóm các môn liên quan?'))change({...tree,requirementGroups:tree.requirementGroups.filter((_,i)=>i!==index)});}}>Xóa nhóm</button></details>
            </section>)}
            <div className="cb-inline">{Object.entries(kinds).filter(([kind])=>kind!=='ELECTIVE_SPECIALIZATION').map(([kind,label])=><button key={kind} className="outline"
              disabled={kind.startsWith('ELECTIVE') && tree.requirementGroups.some(g=>g.kind?.startsWith('ELECTIVE'))}
              onClick={()=>{const code='GROUP_'+Date.now();change({...tree,requirementGroups:[...tree.requirementGroups,{groupCode:code,groupName:label,kind,specializationCodes:[track],requiredCredits:0,eligibleCourseCodes:[],completionOptions:[]}]});}}>Thêm {label.toLocaleLowerCase('vi')}</button>)}</div>
          </fieldset>}
        </>}
      </>}
      {step === 3 && <section className="cb-review"><h4>4. Kiểm tra toàn bộ CTĐT</h4><p>Lưu bản nháp và kiểm tra tất cả cây trước khi công bố. Sinh viên giữ phiên bản đã gán.</p>
        <div className="cb-review-grid">{meta.specializations.map(s=>{const t=trees[s.code], summary=validation?.creditSummariesBySpecialization?.[s.code];return <article key={s.code}><h4>{s.name}</h4>
          <p>{treeCourses(t).length} học phần · {t?.semesters?.length ?? 0} học kỳ{changed.includes(s.code)?' · Chưa lưu':''}</p>
          <dl><dt>Bắt buộc</dt><dd>{summary?.mandatoryCredits ?? 'Chưa kiểm tra'}</dd><dt>Chỉ tiêu tự chọn</dt><dd>{summary?.electiveCredits ?? 'Chưa kiểm tra'}</dd>
            <dt>Phương án tốt nghiệp</dt><dd>{summary?.graduationCredits ?? 'Chưa kiểm tra'}</dd><dt>Tổng yêu cầu</dt><dd>{summary?.requiredTotal ?? 'Chưa kiểm tra'}</dd></dl>
          <button className="outline" onClick={()=>{setTrack(s.code);setStep(1);}}>Xem cây {s.name}</button></article>;})}</div>
        {validation && <div role="status" className={validation.valid?'cb-valid':'cb-warnings'}><p>{validation.valid?'Tất cả cây hợp lệ.':'CTĐT chưa đủ điều kiện công bố.'}</p>
          {validation.errors?.map((message,i)=><p key={i} className="form-error">{treeValidationMessage(message,meta)}<small>{validationLocation(message,trees,meta.specializations)}</small></p>)}
          {validation.warnings?.map((w,i)=><p key={i}>{treeValidationMessage(w,meta)}</p>)}
        </div>}
      </section>}
      <footer className="cb-actions">
        <div className="cb-inline"><button className="outline" disabled={busy || step===0} onClick={()=>setStep(step-1)}>Quay lại</button>
          <button className="outline" disabled={busy || step===3} onClick={()=>setStep(step+1)}>Tiếp tục</button></div>
        {!locked && <button className="primary" disabled={busy} onClick={save}>{busy?'Đang xử lý…':'Lưu bản nháp'}</button>}
        <button className="outline" disabled={busy || dirty || !detail} onClick={()=>run('validate')}>Kiểm tra toàn bộ cây</button>
        {!locked && <button className="primary" disabled={busy || !canPublishTree(detail,dirty,validation)} onClick={()=>run('publish')}>Công bố toàn bộ CTĐT</button>}
      </footer>
    </>}
    {error && <div role="alert" className="cb-warnings"><p className="form-error">{treeValidationMessage(error,meta)}</p><button className="outline" disabled={busy} onClick={()=>{
      if(confirmTreeSwitch(dirty,window.confirm))setReload(v=>v+1);
    }}>Tải lại dữ liệu</button></div>}
    {adding !== null && tree && !locked && <BuilderDialog label={'Thêm học phần vào kỳ ' + adding} close={()=>setAdding(null)}>
      <div className="cb-heading"><h3>Thêm học phần vào kỳ {adding}</h3><button className="outline" onClick={()=>setAdding(null)}>Đóng</button></div>
      <label className="field-label">Nhóm cho các học phần mới<select value={groupCode} onChange={e=>setGroupCode(e.target.value)}><option value="">Phân nhóm sau</option>
        {(tree.requirementGroups ?? []).map(g=><option key={g.groupCode} value={g.groupCode}>{g.groupName || kinds[g.kind]}</option>)}</select></label>
      <CoursePicker label="Danh mục học phần chung" items={catalog} value={[]} applyLabel="Thêm các học phần đã chọn" reasonFor={c=>selected.has(c.courseCode)?'Đã có trong cây chuyên ngành':''}
        onApply={codes=>{try {change(addCourses(tree,adding,codes,catalog,groupCode));setAdding(null);} catch(e){setError(e.message);}}}/>
    </BuilderDialog>}
    {currentCourse && <BuilderDialog label={'Chi tiết ' + editing} close={()=>setEditing('')}>
      <div className="cb-heading"><h3>{currentCourse.courseCode} · {currentCourse.courseName}</h3><button className="outline" disabled={busy} onClick={()=>setEditing('')}>Đóng chi tiết</button></div>
      {error && <p role="alert" className="form-error">{treeValidationMessage(error,meta)}</p>}
      <fieldset disabled={locked || busy}><div className="form-grid"><Input label="Học kỳ dự kiến" type="number" min="1" step="1" value={currentCourse.semesterNumber} change={v=>{
        const n=Number(v);if(Number.isInteger(n)&&n>0)change(editCourse(tree,editing,{semesterNumber:n}));
      }}/><label className="field-label">Nhóm yêu cầu<select value={courseRequirement(tree,editing)?.groupCode ?? ''} onChange={e=>change(deriveMandatory(assignTreeCourse(tree,editing,e.target.value),catalog))}>
        <option value="">Chọn nhóm yêu cầu</option>{(tree.requirementGroups ?? []).map(g=><option key={g.groupCode} value={g.groupCode}>{g.groupName || kinds[g.kind]}</option>)}</select></label></div>
      <CoursePicker label="Học phần tiên quyết" items={catalog} value={currentCourse.prerequisiteCourseCodes ?? []} disabled={locked || busy} reasonFor={c=>prerequisiteReason(tree,editing,c.courseCode)} onApply={codes=>{
        const bad=codes.find(c=>prerequisiteReason(tree,editing,c));
        if(bad){setError('Tiên quyết '+bad+': '+prerequisiteReason(tree,editing,bad));return;}
        change(editCourse(tree,editing,{prerequisiteCourseCodes:codes}));notify('Đã chọn tiên quyết; cần lưu bản nháp');
      }}/>
      {!locked && <div className="cb-danger"><button className="outline" onClick={()=>{
        if(window.confirm('Bỏ '+editing+' khỏi cây? Các môn phụ thuộc sẽ cần kiểm tra lại tiên quyết.')){change(deriveMandatory(removeTreeCourse(tree,editing),catalog));setEditing('');}
      }}>Bỏ học phần khỏi cây</button></div>}</fieldset>
    </BuilderDialog>}
  </section>;
}
