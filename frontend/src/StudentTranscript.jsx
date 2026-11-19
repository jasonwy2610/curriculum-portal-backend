import React,{useEffect,useState} from 'react';
import {Search,ChevronDown,ChevronRight,BookOpen,History,RefreshCw,AlertCircle} from 'lucide-react';
import {numberText,semesterText,selectTranscript} from './student-transcript.js';
import './student-transcript.css';

const dateText=x=>x?new Date(x).toLocaleString('vi-VN',{timeZone:'Asia/Ho_Chi_Minh'}):'Chưa có ngày hoàn thành';
export function TranscriptStatus({row}){return <span className="sr-badges"><span className={'sr-status '+(row.status==='PASSED'?'passed':row.status==='FAILED'?'failed':'unknown')}>
  {row.status==='PASSED'?'Đạt':row.status==='FAILED'?'Không đạt':'Chưa xác minh'}</span>{row.retake&&<span className="sr-retake">Học lại</span>}</span>}
export function TranscriptView({data,filters,setFilters,expanded,setExpanded,detail,setDetail,onReload}){
  const selected=selectTranscript(data,filters), summary=data.summary??{};
  const update=(key,value)=>{
    const next={...filters,[key]:value,...(key==='year'?{term:'all'}:{})};setFilters(next);
    const groups=selectTranscript(data,next).groups;
    if(key==='search'&&value.trim())setExpanded(groups.map(g=>g.semesterCode));
    else if(key==='year'||key==='term')setExpanded(groups.slice(0,1).map(g=>g.semesterCode));
  };
  const toggle=code=>setExpanded(expanded.includes(code)?expanded.filter(c=>c!==code):[...expanded,code]);
  const rowsForDetail=(data.attempts??[]).filter(r=>r.courseCode===detail?.courseCode);
  const detailButton=row=><button className="sr-detail" aria-label={'Lịch sử '+row.courseCode} onClick={()=>setDetail(row)}><History size={15}/><span>Chi tiết</span></button>;
  return <div className="sr-page">
    <div className="sr-overview">
      <article><span>GPA tích lũy hệ 4</span><strong>{numberText(summary.cumulativeGpa4,2)}</strong><small>Toàn bộ kết quả theo CTĐT đã gán</small></article>
      <article><span>Tín chỉ tích lũy đã đạt</span><strong>{numberText(summary.academicEarnedCredits)}</strong><small>Mỗi học phần đạt được tính một lần</small></article>
      <article><span>GPA học kỳ đang chọn</span><strong>{numberText(selected.selectedSemester?.gpa4,2)}</strong><small>{selected.selectedSemester?semesterText(filters.term):'Chọn một học kỳ để xem GPA'}</small></article>
      <article><span>Số môn chưa đạt</span><strong className={selected.failedCourses?'sr-danger':''}>{selected.failedCourses}</strong><small>Trong phạm vi năm, kỳ và tìm kiếm</small></article>
    </div>
    <p className="sr-credit-note">Tín chỉ học tập đạt khác tín chỉ công nhận tốt nghiệp: <b>{numberText(summary.graduationRecognizedCredits)}</b> tín chỉ đã được công nhận theo nhóm yêu cầu. Môn tự chọn học dư vẫn giữ điểm và được xét GPA theo quy định.</p>
    <section className="panel sr-results"><div className="sr-section-head"><div><h2>Bảng điểm của bạn</h2><p>GPA theo kết quả chính thức của học kỳ; tìm kiếm và chế độ xem chỉ lọc danh sách môn.</p></div><button className="outline" onClick={onReload} aria-label="Tải lại bảng điểm"><RefreshCw size={16}/></button></div>
      <div className="sr-filters">
        <label className="sr-search"><span>Tìm học phần</span><div><Search size={17}/><input value={filters.search} onChange={e=>update('search',e.target.value)} placeholder="Mã hoặc tên học phần"/></div></label>
        <label>Năm học<select value={filters.year} onChange={e=>update('year',e.target.value)}><option value="all">Tất cả năm học</option>{selected.years.map(y=><option key={y} value={y}>{y}–{Number(y)+1}</option>)}</select></label>
        <label>Học kỳ<select value={filters.term} onChange={e=>update('term',e.target.value)}><option value="all">Tất cả học kỳ</option>{selected.terms.map(s=><option key={s.semesterCode} value={s.semesterCode}>{semesterText(s.semesterCode)}</option>)}</select></label>
        <label>Chế độ xem<select value={filters.mode} onChange={e=>update('mode',e.target.value)}><option value="attempts">Tất cả lần học</option><option value="highest">Điểm cao nhất mỗi học phần</option></select></label>
      </div>
      {selected.issues.length>0&&<details className="sr-issues"><summary><AlertCircle size={16}/> {selected.issues.length} thông tin cần bổ sung hoặc xác minh</summary><ul>{selected.issues.map((i,n)=><li key={n}>{i.courseCode&&<b>{i.courseCode} · </b>}<span>{i.semesterCode?semesterText(i.semesterCode):i.courseCode?'Mọi lần học':'Toàn bộ bảng điểm'}: </span>{i.message}</li>)}</ul><p>Chỉ số bị ảnh hưởng hiển thị “—”; không dùng giá trị tạm thay cho GPA.</p></details>}
      <div className="sr-list-actions"><span>{selected.rows.length} lần học · {selected.groups.length} học kỳ</span><div><button onClick={()=>setExpanded(selected.groups.map(g=>g.semesterCode))}>Mở tất cả</button><button onClick={()=>setExpanded([])}>Thu gọn tất cả</button></div></div>
      {!selected.groups.length&&<div className="sr-empty" role="status"><BookOpen size={30}/><h3>{data.attempts?.length?'Không có kết quả phù hợp':'Chưa có kết quả học tập'}</h3><p>{data.attempts?.length?'Thử thay đổi năm học, học kỳ hoặc từ khóa.':'Kết quả sẽ xuất hiện khi giáo vụ ghi nhận lần học.'}</p>{data.attempts?.length>0&&<button className="outline" onClick={()=>setFilters({search:'',year:'all',term:'all',mode:'attempts'})}>Xóa bộ lọc</button>}</div>}
      {selected.groups.map(g=>{const open=expanded.includes(g.semesterCode);return <section className="sr-term" key={g.semesterCode}>
        <button className="sr-term-heading" aria-expanded={open} onClick={()=>toggle(g.semesterCode)}><span>{open?<ChevronDown size={18}/>:<ChevronRight size={18}/>}<b>{semesterText(g.semesterCode)}</b><small>{g.rows.length} lần học hiển thị</small></span><span className="sr-term-metrics"><span>GPA <b>{numberText(g.gpa4,2)}</b></span><span>Đăng học <b>{numberText(g.attemptedCredits)} TC</b></span><span>Đạt <b>{numberText(g.earnedCredits)} TC</b></span></span></button>
        {open&&<><div className="sr-table-wrap"><table className="sr-table"><thead><tr>{['Mã học phần','Tên học phần','Tín chỉ','Lần học','Hệ 10','Hệ 4','Điểm chữ','Trạng thái','Chi tiết'].map(h=><th key={h}>{h}</th>)}</tr></thead><tbody>{g.rows.map((r,i)=><tr key={r.recordId+'-'+(r.attemptId??i)}><td className="sr-code">{r.courseCode}</td><td>{r.courseName||'Chưa có tên học phần'}</td><td>{numberText(r.credits)}</td><td>{r.attemptNumber??'—'}</td><td>{numberText(r.score10,1)}</td><td>{numberText(r.score4,1)}</td><td>{r.letterGrade??'—'}</td><td><TranscriptStatus row={r}/></td><td>{detailButton(r)}</td></tr>)}</tbody></table></div>
          <div className="sr-mobile-cards">{g.rows.map((r,i)=><article key={r.recordId+'-'+(r.attemptId??i)}><header><div><small>{r.courseCode}</small><h3>{r.courseName||'Chưa có tên học phần'}</h3></div>{detailButton(r)}</header><TranscriptStatus row={r}/><dl>{[['Tín chỉ',numberText(r.credits)],['Lần học',r.attemptNumber??'—'],['Hệ 10',numberText(r.score10,1)],['Hệ 4',numberText(r.score4,1)],['Điểm chữ',r.letterGrade??'—']].map(([k,v])=><div key={k}><dt>{k}</dt><dd>{v}</dd></div>)}</dl></article>)}</div>
          <p className="sr-term-footer">Tổng kết toàn học kỳ: {g.attemptCount} lần học · {numberText(g.gpaCredits)} tín chỉ tính GPA. Tín chỉ đăng học/đạt trong kỳ giữ từng lần học; tín chỉ tích lũy chỉ tính mỗi môn đạt một lần.</p></>}
      </section>})}
    </section>
    {detail&&<div className="modal-backdrop" onMouseDown={e=>e.target===e.currentTarget&&setDetail(null)}><section className="modal sr-history" role="dialog" aria-modal="true" aria-labelledby="sr-history-title"><header><div><small>LỊCH SỬ HỌC PHẦN · {detail.courseCode}</small><h2 id="sr-history-title">{detail.courseName||'Chưa có tên học phần'}</h2></div><button className="outline" aria-label="Đóng lịch sử học phần" onClick={()=>setDetail(null)}>×</button></header><p>{numberText(detail.credits)} tín chỉ · Hiển thị mọi lần học, kể cả ngoài bộ lọc.</p><ol>{rowsForDetail.map((r,i)=><li key={r.recordId+'-'+(r.attemptId??i)}><div><b>Lần {r.attemptNumber??'chưa xác định'} · {semesterText(r.semesterCode)}</b><TranscriptStatus row={r}/></div><p>Hệ 10: <b>{numberText(r.score10,1)}</b> · Hệ 4: <b>{numberText(r.score4,1)}</b> · Điểm chữ: <b>{r.letterGrade??'—'}</b></p><small>{dateText(r.completedAt)}</small></li>)}</ol><button className="primary" onClick={()=>setDetail(null)}>Đóng lịch sử</button></section></div>}
  </div>;
}
export default function StudentTranscript({token,request}){
  const [data,setData]=useState(null),[loading,setLoading]=useState(true),[error,setError]=useState(''),[revision,setRevision]=useState(0);
  const [filters,setFilters]=useState({search:'',year:'all',term:'all',mode:'attempts'}),[expanded,setExpanded]=useState([]),[detail,setDetail]=useState(null);
  useEffect(()=>{let active=true;setLoading(true);setError('');setData(null);setDetail(null);
    request('/api/v1/student/academic-records/transcript',{token}).then(value=>{
      if(!value?.summary||!Array.isArray(value.attempts)||!Array.isArray(value.semesters)||!Array.isArray(value.issues))
        throw new Error('Dữ liệu bảng điểm chưa đầy đủ. Vui lòng thử lại hoặc liên hệ giáo vụ.');
      if(active){setData(value);setExpanded(value.semesters.map(s=>s.semesterCode).sort().reverse().slice(0,1));}})
      .catch(e=>{if(active)setError(e.message||'Chưa thể tải bảng điểm. Vui lòng thử lại.');}).finally(()=>{if(active)setLoading(false);});
    return()=>{active=false;};},[token,request,revision]);
  if(loading)return <div className="panel sr-loading" role="status"><span/>Đang tải bảng điểm và GPA chính thức...</div>;
  if(error)return <div className="panel sr-error" role="alert"><AlertCircle/><h2>Chưa thể tải kết quả học tập</h2><p>{error}</p><button className="outline" onClick={()=>setRevision(r=>r+1)}>Thử lại</button></div>;
  return data?<TranscriptView {...{data,filters,setFilters,expanded,setExpanded,detail,setDetail}} onReload={()=>setRevision(r=>r+1)}/>:null;
}
