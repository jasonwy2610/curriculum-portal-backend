import React,{useEffect,useState} from 'react';
import {Search,BookOpen,History,RefreshCw,AlertCircle} from 'lucide-react';
import {numberText,semesterText,termLabel,selectTranscript,transcriptPlanState} from './student-transcript.js';
import './student-transcript.css';

const dateText=x=>x?new Date(x).toLocaleString('vi-VN',{timeZone:'Asia/Ho_Chi_Minh'}):'Chưa có ngày hoàn thành';
const plannedNames={NOT_STUDIED:'Chưa có kết quả',HAS_HISTORY:'Có kết quả ở kỳ thực tế',GROUP_COMPLETE:'Nhóm yêu cầu đã hoàn thành',
  RECOGNIZED_REPLACEMENT:'Đã công nhận qua môn thay thế',HISTORY_UNVERIFIED:'Có bản ghi cần xác minh'};
export function TranscriptStatus({row}){return <span className="sr-badges"><span className={'sr-status '+(row.planned&&!row.hasActualResult?'planned':row.status==='PASSED'?'passed':row.status==='FAILED'?'failed':'unknown')}>
  {row.planned&&!row.hasActualResult?(row.state==='HAS_HISTORY_ELSEWHERE'?(row.passedElsewhere?'Đã đạt ở kỳ khác':'Có kết quả ở kỳ khác'):row.state==='GROUP_COMPLETE'?'Chưa có kết quả':plannedNames[row.state]??'Chưa có kết quả'):row.status==='PASSED'?'Đạt':row.status==='FAILED'?'Không đạt':'Chưa xác minh'}</span>
  {row.planned&&!row.hasActualResult&&row.state==='GROUP_COMPLETE'&&<span className="sr-status planned">Nhóm yêu cầu đã hoàn thành</span>}
  {row.hasActualResult&&row.state==='RECOGNIZED_REPLACEMENT'&&<span className="sr-status planned">Đã công nhận qua môn thay thế</span>}
  {row.retake&&<span className="sr-retake">Học lại</span>}</span>}
function PlanReference({row}) {
  if(!(row.plannedSemesterNumber||row.actualSemesterCodes?.length||row.replacementCourseCodes?.length))return null;
  return <small className="sr-plan-reference">{row.hasActualResult&&row.plannedSemesterNumber&&<>Dự kiến: kỳ {row.plannedSemesterNumber}. </>}{!row.hasActualResult&&row.actualSemesterCodes?.length>0&&<>Thực tế: {row.actualSemesterCodes.map(semesterText).join('; ')}. </>}
    {row.replacementCourseCodes?.length>0&&<>Môn nguồn: {row.replacementCourseCodes.join(', ')}. </>}
</small>;
}
export function TranscriptView({data,filters,setFilters,detail,setDetail,onReload}){
  const selected=selectTranscript(data,filters),summary=data.summary??{},planState=transcriptPlanState(data);
  const update=(key,value)=>setFilters({...filters,[key]:value,...(key==='year'?{term:'all'}:{})});
  const detailCodes=new Set([detail?.courseCode,...(detail?.replacementCourseCodes??[])]);
  const rowsForDetail=(data.attempts??[]).filter(r=>detailCodes.has(r.courseCode));
  const detailButton=row=><button className="sr-detail" aria-label={'Lịch sử '+row.courseCode} onClick={()=>setDetail(row)}><History size={15}/><span>Chi tiết</span></button>;
  return <div className="sr-page">
    <div className="sr-overview">
      <article><span>GPA tích lũy hệ 4</span><strong>{numberText(summary.cumulativeGpa4,2)}</strong></article>
      <article><span>Tín chỉ tích lũy đã đạt</span><strong>{numberText(summary.academicEarnedCredits)}</strong></article>
      <article><span>GPA học kỳ đang chọn</span><strong>{numberText(selected.selectedSemester?.gpa4,2)}</strong><small>{selected.selectedSemester?termLabel(selected.selectedSemester):'Chọn một học kỳ để xem GPA'}</small></article>
      <article><span>Số môn chưa đạt</span><strong className={selected.failedCourses?'sr-danger':''}>{selected.failedCourses}</strong><small>Theo bộ lọc</small></article>
    </div>
    <p className="sr-credit-note">Tín chỉ công nhận tốt nghiệp: <b>{numberText(summary.graduationRecognizedCredits)}</b></p>
    <section className="panel sr-results"><div className="sr-section-head"><div><h2>Bảng điểm của bạn</h2></div><button className="outline" onClick={onReload} aria-label="Tải lại bảng điểm"><RefreshCw size={16}/></button></div>
      <div className="sr-filters">
        <label className="sr-search"><span>Tìm học phần</span><div><Search size={17}/><input value={filters.search} onChange={e=>update('search',e.target.value)} placeholder="Mã hoặc tên học phần"/></div></label>
        <label>Năm học<select value={filters.year} onChange={e=>update('year',e.target.value)}><option value="all">Tất cả năm học</option>{selected.hasPlan&&<option value="planned">Chưa xác định năm học</option>}{selected.years.map(y=><option key={y} value={y}>{y}–{Number(y)+1}</option>)}</select></label>
        <label>Học kỳ<select value={filters.term} onChange={e=>update('term',e.target.value)}><option value="all">Tất cả học kỳ</option>{selected.terms.map(s=><option key={s.semesterCode} value={s.semesterCode}>{termLabel(s)}</option>)}</select></label>
        <label>Chế độ xem<select value={filters.mode} onChange={e=>update('mode',e.target.value)}><option value="attempts">Tất cả lần học</option><option value="highest">Điểm cao nhất mỗi học phần</option></select></label>
      </div>
      {planState.problem&&<p className="sr-plan-note" role="alert"><b>Chưa thể hiển thị đầy đủ học phần CTĐT. </b>{planState.message}</p>}
      {selected.issues.length>0&&<details className="sr-issues"><summary><AlertCircle size={16}/> {selected.issues.length} thông tin cần bổ sung hoặc xác minh</summary><ul>{selected.issues.map((i,n)=><li key={n}>{i.courseCode&&<b>{i.courseCode} · </b>}<span>{i.semesterCode?.startsWith('PLAN:')?'Học kỳ dự kiến '+i.semesterCode.slice(5):i.semesterCode?semesterText(i.semesterCode):i.courseCode?'Mọi lần học':'Toàn bộ bảng điểm'}: </span>{i.message}</li>)}</ul><p>Chỉ số chưa đủ dữ liệu hiển thị “—”.</p></details>}
      <div className="sr-list-actions"><span>{selected.groups.length} học kỳ hiển thị</span><button onClick={()=>setFilters({search:'',year:'all',term:'all',mode:'attempts'})}>Xóa bộ lọc</button></div>
      {!selected.groups.length&&<div className="sr-empty" role="status"><BookOpen size={30}/><h3>{data.attempts?.length||selected.hasPlan?'Không có kết quả phù hợp':'Chưa có kết quả học tập'}</h3><p>{data.attempts?.length||selected.hasPlan?'Thử đổi từ khóa hoặc bộ lọc.':'Chưa có lần học được ghi nhận.'}</p></div>}
      {selected.groups.map((g,index)=> <React.Fragment key={g.semesterCode}>
        <section className={'sr-term '+(g.planned?'sr-planned-term':'sr-actual-term')} data-semester-key={g.semesterCode}>
          <header className="sr-term-heading"><div><h3>{termLabel(g)}</h3><small>{g.planned&&'Năm học: — · '}{numberText(g.plannedCourseCount)} môn theo CTĐT · {numberText(g.resultCourseCount)} môn có kết quả{g.planned?' ở kỳ thực tế':''}</small></div>
            <div className="sr-term-metrics"><span>GPA <b>{numberText(g.gpa4,2)}</b></span><span>Học thực tế <b>{numberText(g.attemptedCredits)} TC</b></span><span>Đạt thực tế <b>{numberText(g.earnedCredits)} TC</b></span></div></header>
          <div className="sr-table-wrap"><table className="sr-table"><thead><tr>{['Mã học phần','Tên học phần','Tín chỉ','Lần học','Hệ 10','Hệ 4','Điểm chữ','Trạng thái','Chi tiết'].map(h=><th key={h}>{h}</th>)}</tr></thead><tbody>{g.rows.map((r,i)=><tr key={r.rowKey??r.recordId+'-'+(r.attemptId??i)}><td className="sr-code">{r.courseCode}</td><td>{r.courseName||'Chưa có tên học phần'}<PlanReference row={r}/></td><td>{numberText(r.credits)}</td><td>{r.attemptNumber??'—'}</td><td>{numberText(r.score10,1)}</td><td>{numberText(r.score4,1)}</td><td>{r.letterGrade??'—'}</td><td><TranscriptStatus row={r}/></td><td>{detailButton(r)}</td></tr>)}</tbody></table></div>
          <div className="sr-mobile-cards">{g.rows.map((r,i)=><article key={r.rowKey??r.recordId+'-'+(r.attemptId??i)}><header><div><small>{r.courseCode}</small><h3>{r.courseName||'Chưa có tên học phần'}</h3></div>{detailButton(r)}</header><TranscriptStatus row={r}/><PlanReference row={r}/><dl>{[['Tín chỉ',numberText(r.credits)],['Lần học',r.attemptNumber??'—'],['Hệ 10',numberText(r.score10,1)],['Hệ 4',numberText(r.score4,1)],['Điểm chữ',r.letterGrade??'—']].map(([k,v])=><div key={k}><dt>{k}</dt><dd>{v}</dd></div>)}</dl></article>)}</div>
          {!g.rows.length&&filters.search.trim()?<p className="sr-period-empty">Không có học phần phù hợp bộ lọc.</p>:g.planned&&!g.rows.length&&g.resultCourseCount>0?<p className="sr-period-empty">Các học phần đã có kết quả ở kỳ thực tế.</p>:g.planned&&g.rows.every(r=>r.state==='NOT_STUDIED')?<p className="sr-period-empty">Chưa có kết quả học tập</p>:!g.rows.length&&<p className="sr-period-empty">{g.attemptCount?'Không có lần học phù hợp bộ lọc.':'Chưa có kết quả học tập'}</p>}
          {!g.planned&&<p className="sr-term-footer">{g.attemptCount} lần học · {numberText(g.gpaCredits)} tín chỉ tính GPA</p>}
        </section>
      </React.Fragment>)}
    </section>
    {detail&&<div className="modal-backdrop" onMouseDown={e=>e.target===e.currentTarget&&setDetail(null)}><section className="modal sr-history" role="dialog" aria-modal="true" aria-labelledby="sr-history-title"><header><div><small>LỊCH SỬ HỌC PHẦN · {detail.courseCode}</small><h2 id="sr-history-title">{detail.courseName||'Chưa có tên học phần'}</h2></div><button className="outline" aria-label="Đóng lịch sử học phần" onClick={()=>setDetail(null)}>×</button></header><p>{numberText(detail.credits)} tín chỉ · Tất cả lần học</p><PlanReference row={detail}/>
      {!rowsForDetail.length&&<p>Chưa có lần học được ghi nhận.</p>}
      <ol>{rowsForDetail.map((r,i)=><li key={r.recordId+'-'+(r.attemptId??i)}><div><b>{r.courseCode} · Lần {r.attemptNumber??'chưa xác định'} · {semesterText(r.semesterCode)}</b><TranscriptStatus row={r}/></div><p>Hệ 10: <b>{numberText(r.score10,1)}</b> · Hệ 4: <b>{numberText(r.score4,1)}</b> · Điểm chữ: <b>{r.letterGrade??'—'}</b></p><small>{dateText(r.completedAt)}</small></li>)}</ol><button className="primary" onClick={()=>setDetail(null)}>Đóng lịch sử</button></section></div>}
  </div>;
}
export default function StudentTranscript({token,request}){
  const [data,setData]=useState(null),[loading,setLoading]=useState(true),[error,setError]=useState(''),[revision,setRevision]=useState(0);
  const [filters,setFilters]=useState({search:'',year:'all',term:'all',mode:'attempts'}),[detail,setDetail]=useState(null);
  useEffect(()=>{let active=true;setLoading(true);setError('');setData(null);setDetail(null);
    request('/api/v1/student/academic-records/transcript',{token}).then(value=>{
      if(!value?.summary||!Array.isArray(value.attempts)||!Array.isArray(value.semesters)||!Array.isArray(value.issues))throw new Error('Dữ liệu bảng điểm chưa đầy đủ. Vui lòng thử lại hoặc liên hệ giáo vụ.');
      if(active){setData(value);setFilters({search:'',year:'all',term:'all',mode:'attempts'});}})
      .catch(e=>{if(active)setError(e.message||'Chưa thể tải bảng điểm. Vui lòng thử lại.');}).finally(()=>{if(active)setLoading(false);});
    return()=>{active=false;};},[token,request,revision]);
  if(loading)return <div className="panel sr-loading" role="status"><span/>Đang tải bảng điểm...</div>;
  if(error)return <div className="panel sr-error" role="alert"><AlertCircle/><h2>Chưa thể tải kết quả học tập</h2><p>{error}</p><button className="outline" onClick={()=>setRevision(r=>r+1)}>Thử lại</button></div>;
  return data?<TranscriptView {...{data,filters,setFilters,detail,setDetail}} onReload={()=>setRevision(r=>r+1)}/>:null;
}
