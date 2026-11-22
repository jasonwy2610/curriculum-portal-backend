import React, {useState} from 'react';
import CoursePicker from './CoursePicker.jsx';
import {treeCourses} from './curriculum-builder.js';
import {graduationNames, graduationLabel, graduationGuide, graduationSummary, graduationCatalog,
  graduationConflict, suggestedGraduationCourses} from './graduation-options.js';

export default function GraduationOptions({group, tree, catalog, onChange, readOnly = false}) {
  const [picker, setPicker] = useState(null), [showAll, setShowAll] = useState(false);
  const options = group.completionOptions ?? [], items = graduationCatalog(tree,catalog);
  function update(index, patch) {onChange?.({...group,completionOptions:options.map((o,i)=>i===index?{...o,...patch}:o)});}
  function add(optionCode) {onChange?.({...group,completionOptions:[...options,{optionCode,courseCodes:[]}]});}
  return <section className="go-workspace" aria-label={'Phương án tốt nghiệp · '+(group.groupName || group.groupCode)}>
    <div className="go-guide"><b>{graduationGuide(group,tree,catalog)}</b>


      <p>Phương án tốt nghiệp độc lập với nhóm tự chọn và quy tắc thay thế.</p>
    </div>
    {!options.length && <p className="cb-empty">Chưa cấu hình phương án tốt nghiệp.</p>}
    {options.map((option,index)=>{
      const summary=graduationSummary(option,group,tree,catalog);
      const suggested=suggestedGraduationCourses(items,tree,group,option);
      return <details className="go-card" key={index} open>
        <summary><span><b>{graduationLabel(option.optionCode)}</b><small>{summary.total ?? '—'}/{summary.required ?? '—'} tín chỉ · Học kỳ: {summary.semesters.join(', ') || 'Chưa xác định'}</small></span>
          <em className={'go-status go-'+summary.state}>{summary.status}</em></summary>
        <p className="field-hint">Mã phương án: <b>{option.optionCode || 'Chưa đặt'}</b></p>
        {!readOnly && <div className="form-grid"><label className="field-label">Tên phương án<select value={option.optionCode} onChange={e=>{update(index,{optionCode:e.target.value==='__CUSTOM__'?'':e.target.value});setPicker(null);}}>
          {!graduationNames[option.optionCode] && <option value={option.optionCode}>{graduationLabel(option.optionCode)}</option>}
          {Object.entries(graduationNames).map(([code,name])=><option key={code} value={code} disabled={options.some((o,i)=>i!==index&&o.optionCode===code)}>{name} ({code})</option>)}
          <option value="__CUSTOM__">Phương án khác</option></select></label>
          {!graduationNames[option.optionCode] && <label className="field-label">Mã phương án khác<input value={option.optionCode} onChange={e=>update(index,{optionCode:e.target.value})}/></label>}
        </div>}
        <ul className="go-courses">{summary.rows.map(c=><li key={c.courseCode}><span><b>{c.courseName}</b><small>{c.courseCode}</small></span>
          <span>{c.credits ?? 'Chưa rõ'} tín chỉ<small>Học kỳ {c.semesterNumber ?? 'chưa xác định'}</small></span></li>)}</ul>
        <p className="go-total">Tổng phương án: <b>{summary.total ?? 'Chưa đủ dữ liệu'}/{summary.required ?? 'Chưa đặt'} tín chỉ</b></p>
        {summary.issues.length>0 && <ul className="go-issues" aria-label={'Cần kiểm tra '+graduationLabel(option.optionCode)}>{summary.issues.map((issue,i)=><li key={i}>{graduationLabel(option.optionCode)} · {issue}</li>)}</ul>}
        {summary.state==='short' && <p className="go-conflict">Cần bổ sung {summary.required-summary.total} tín chỉ cho phương án này.</p>}
        {!readOnly && <>
          <div className="cb-inline"><button type="button" className="outline" onClick={()=>{setPicker(picker===index?null:index);setShowAll(false);}}>{picker===index?'Đóng bộ chọn':'Chọn học phần'}</button></div>
          {picker===index && <div className="go-picker"><p>Gợi ý học phần tốt nghiệp. Có thể xem toàn bộ danh mục.</p>
            <label className="go-all"><input type="checkbox" checked={showAll} onChange={e=>setShowAll(e.target.checked)}/> Hiển thị tất cả học phần</label>
            {!showAll && !suggested.length && <p>Chưa có gợi ý phù hợp. Bật hiển thị tất cả để tìm học phần trong danh mục dùng chung.</p>}
            <CoursePicker label={'Chọn học phần · '+graduationLabel(option.optionCode)} items={showAll?items:suggested} value={option.courseCodes ?? []}
              showSelectionCredits showSemester reasonFor={c=>!Number.isInteger(c.semesterNumber)||c.semesterNumber<1?'Chưa có học kỳ hợp lệ trong cây; thêm môn ở Bước 2 trước':''}
              warningFor={c=>graduationConflict(tree,group.groupCode,c.courseCode)}
              onApply={codes=>{update(index,{courseCodes:[...new Set(codes)]});setPicker(null);}}/>
            <p className="field-hint">Lưu bản nháp sau khi chọn. Kiểm tra cảnh báo trùng nhóm trước công bố.</p>
          </div>}
          <div className="go-remove"><button type="button" className="outline" onClick={()=>{
            if(window.confirm('Xóa '+graduationLabel(option.optionCode)+' khỏi bản nháp? Các học phần trong cây vẫn được giữ.')){
              onChange({...group,completionOptions:options.filter((_,i)=>i!==index)});setPicker(null);
            }
          }}>Xóa phương án</button></div>
        </>}
      </details>;
    })}
    {!readOnly && <div className="cb-inline go-add">
      {Object.entries(graduationNames).map(([code,name])=><button type="button" className="outline" key={code} disabled={options.some(o=>o.optionCode===code)} onClick={()=>add(code)}>Thêm {name.toLocaleLowerCase('vi')}</button>)}
      <button type="button" className="outline" onClick={()=>add('')}>Thêm phương án khác</button>
    </div>}
  </section>;
}

export function GraduationGroups({tree, catalog = treeCourses(tree)}) {
  return <>{(tree?.requirementGroups ?? []).filter(g=>g.kind==='GRADUATION').map((g,i)=><GraduationOptions key={i} group={g} tree={tree} catalog={catalog} readOnly/>)}</>;
}
