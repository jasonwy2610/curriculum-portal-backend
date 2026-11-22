import React from 'react';
import {learningLabel,recognitionLabel,replacementShape} from './replacement-presentation.js';
const date=value=>value?new Date(value).toLocaleString('vi-VN',{timeZone:'Asia/Ho_Chi_Minh'}):'Chưa có dữ liệu lịch sử';
function Courses({courses=[]}){return <ul className="replacement-course-list">{courses.map(c=><li key={c.courseCode}>
  <div><b>{c.courseName||'Chưa có tên học phần'}</b><small>{c.courseCode}</small></div>
  <span>{c.credits??'—'} tín chỉ</span><span className={'replacement-learning '+(c.learningStatus??'UNKNOWN')}>{learningLabel(c)}</span>
</li>)}</ul>}
export default function ReplacementEvidenceDetails({rules=[],originalCourse}){
  if(!rules.length)return <p>Chưa có thông tin thay thế cho học phần này.</p>;
  return <div className="replacement-evidence">{rules.map((rule,index)=>{
    const originals=rule.oldCourses??(rule.oldCourseCodes??[]).map(code=>({...((originalCourse?.courseCode===code)?originalCourse:{}),courseCode:code}));
    return <article key={rule.ruleId??index}>
      <h4>Quy tắc {replacementShape(rule)}{rule.versionNumber!=null&&<> · Phiên bản {rule.versionNumber}</>}</h4>
      <h5>Môn được thay thế</h5><Courses courses={originals}/>
      <h5>Môn dùng để thay thế</h5><Courses courses={rule.newCourses??(rule.newCourseCodes??[]).map(courseCode=>({courseCode}))}/>
      <dl className="replacement-facts"><div><dt>Công nhận tín chỉ</dt><dd>{recognitionLabel(rule)}</dd></div>
        <div><dt>Hiệu lực</dt><dd>{rule.automaticRecognitionSupported?'Đang áp dụng':'Không áp dụng hiện tại'}</dd></div>
        {(rule.startsAt||rule.effectiveDate)&&<div><dt>Từ ngày</dt><dd>{date(rule.startsAt??rule.effectiveDate)}</dd></div>}
        {rule.endsAt&&<div><dt>Đến trước</dt><dd>{date(rule.endsAt)}</dd></div>}
        {rule.applicableCohorts?.length>0&&<div><dt>Khóa áp dụng</dt><dd>{rule.applicableCohorts.join(', ')}</dd></div>}
        {rule.curriculumVersion!=null&&<div><dt>CTĐT đang xét</dt><dd>Phiên bản {rule.curriculumVersion} · {rule.specializationCode} · {rule.studentCohort}</dd></div>}
      </dl>
      <p className="replacement-conditions">Điều kiện xét mới: đạt tất cả môn nguồn, ngày hoàn thành trong thời gian áp dụng và không trùng nguồn công nhận.</p>
      {rule.historicalEvidence&&<div className="replacement-history"><h5>Bằng chứng công nhận đã lưu</h5>
        {rule.recognizedAt&&<p>Ngày ghi nhận: {date(rule.recognizedAt)}</p>}
        {rule.grade4!=null&&<p>Điểm quy đổi đã lưu: {rule.grade4}</p>}
      </div>}
      {rule.reason&&<p>Lý do: {rule.reason}</p>}{rule.note&&<p>{rule.note}</p>}
      {rule.recognized&&<p>Công nhận tín chỉ theo môn gốc.</p>}
    </article>;
  })}</div>;
}
