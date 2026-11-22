import {treeCourses} from './curriculum-builder.js';

export const graduationNames = {THESIS:'Khóa luận tốt nghiệp', CAPSTONE:'Đồ án/Chuyên đề tốt nghiệp'};
export const graduationName = code => graduationNames[code] ?? (code ? `Phương án ${code}` : 'Phương án chưa đặt mã');
export const graduationLabel = code => graduationNames[code] ? `${graduationNames[code]} (${code})` : graduationName(code);
const unique = values => [...new Set(values ?? [])];
const contains = (group, code) => group.eligibleCourseCodes?.includes(code) || group.completionOptions?.some(o=>o.courseCodes?.includes(code));
export function graduationConflict(tree, groupCode, code) {
  return (tree.requirementGroups ?? []).filter(g=>g.groupCode!==groupCode && g.kind!=='CONDITIONAL' && g.countsTowardGraduation!==false && contains(g,code))
    .map(g=>`Đang thuộc ${g.kind?.startsWith('ELECTIVE')?'nhóm tự chọn':g.kind?.startsWith('MANDATORY')?'nhóm bắt buộc':'nhóm yêu cầu khác'}: ${g.groupName || g.groupCode}.`).join(' ');
}
export function graduationCatalog(tree, catalog) {
  const planned=treeCourses(tree);
  return catalog.map(c=>({...c, semesterNumber:planned.find(p=>p.courseCode===c.courseCode)?.semesterNumber}));
}
export function graduationSummary(option, group, tree, catalog) {
  const codes=option.courseCodes ?? [], planned=treeCourses(tree), issues=[], rows=unique(codes).map(code=>{
    const c=catalog.find(c=>c.courseCode===code), plans=planned.filter(c=>c.courseCode===code);
    const validCredits=Number.isInteger(c?.credits)&&c.credits>0;
    if(!c || !validCredits) issues.push(`${code}: thiếu học phần hoặc số tín chỉ hợp lệ trong danh mục.`);
    if(plans.length!==1 || !Number.isInteger(plans[0]?.semesterNumber) || plans[0].semesterNumber<1)
      issues.push(`${code}: chưa có học kỳ dự kiến hợp lệ, duy nhất trong cây chuyên ngành.`);
    const conflict=graduationConflict(tree,group.groupCode,code);
    if(conflict)issues.push(`${code}: ${conflict} Kiểm tra việc dùng chung học phần trước công bố.`);
    return {courseCode:code,courseName:c?.courseName || plans[0]?.courseName || 'Chưa có tên trong danh mục',
      credits:validCredits?c.credits:null,semesterNumber:plans.length===1?plans[0].semesterNumber:null};
  });
  const repeated=unique(codes.filter((code,i)=>codes.indexOf(code)!==i));
  if(repeated.length)issues.push(`Môn lặp trong cùng phương án: ${repeated.join(', ')}.`);
  if(!option.optionCode?.trim())issues.push('Chưa có mã phương án.');
  if((group.completionOptions ?? []).filter(o=>o.optionCode===option.optionCode).length>1)issues.push('Mã phương án bị trùng trong nhóm.');
  if(!rows.length)issues.push('Chưa chọn học phần.');
  const required=Number.isInteger(group.requiredCredits)&&group.requiredCredits>0?group.requiredCredits:null;
  if(required===null)issues.push('Chỉ tiêu nhóm tốt nghiệp phải là số nguyên dương.');
  const total=rows.some(c=>c.credits===null)?null:rows.reduce((sum,c)=>sum+c.credits,0);
  if(total!==null && required!==null && total>required)issues.push(`Tổng ${total} tín chỉ vượt chỉ tiêu ${required}; mỗi phương án cần đúng chỉ tiêu nhóm tốt nghiệp.`);
  const state=issues.length || total===null || required===null || total>required?'check':total<required?'short':'ready';
  return {rows,total,required,issues,state,semesters:unique(rows.map(c=>c.semesterNumber).filter(n=>Number.isInteger(n)&&n>0)).sort((a,b)=>a-b),
    status:{check:'Cần kiểm tra',short:'Chưa đủ tín chỉ',ready:'Hợp lệ sơ bộ'}[state]};
}
export function graduationGuide(group, tree, catalog) {
  if(group.countsTowardGraduation===false)return 'Nhóm này không được tính vào yêu cầu tín chỉ tốt nghiệp theo cấu hình hiện tại.';
  const options=group.completionOptions ?? [], codes=options.map(o=>o.optionCode);
  if(options.length===2 && codes.includes('THESIS') && codes.includes('CAPSTONE') &&
    options.every(o=>graduationSummary(o,group,tree,catalog).state==='ready'))
    return 'Sinh viên cần hoàn thành 1 trong 2 phương án tốt nghiệp.';
  if(!options.length)return 'Chưa có phương án tốt nghiệp. Thêm phương án để cấu hình yêu cầu này.';
  return `Nhóm đang cấu hình ${options.length} phương án. Sinh viên chỉ cần hoàn thành một phương án hợp lệ của nhóm.`;
}
const plain=value=>String(value ?? '').normalize('NFD').replace(/[\u0300-\u036f]/g,'').replaceAll('đ','d').replaceAll('Đ','D').toLowerCase();
export function suggestedGraduationCourses(catalog, tree, group, option) {
  const inGroup=new Set((group.completionOptions ?? []).flatMap(o=>o.courseCodes ?? []));
  const selected=new Set(option.courseCodes ?? []);
  const planned=new Map(treeCourses(tree).map(c=>[c.courseCode,c]));
  const rank=c=>{
    if(selected.has(c.courseCode))return 0;
    const text=plain(`${c.courseName} ${c.courseCode}`);
    const match=option.optionCode==='THESIS'?/khoa luan|thesis/.test(text):option.optionCode==='CAPSTONE'?/capstone|(?:do an|chuyen de).*tot nghiep/.test(text):false;
    if(match)return 1;
    if(inGroup.has(c.courseCode) || planned.get(c.courseCode)?.requirementGroupCode===group.groupCode)return 2;
    return 3;
  };
  // Keep saved anomalies visible, but avoid suggesting other trees' courses.
  // The full catalogue remains available explicitly, with eligibility reasons.
  return [...catalog].filter(c=>(planned.has(c.courseCode)||selected.has(c.courseCode))&&rank(c)<3)
    .sort((a,b)=>rank(a)-rank(b)||a.courseCode.localeCompare(b.courseCode,'vi'));
}
