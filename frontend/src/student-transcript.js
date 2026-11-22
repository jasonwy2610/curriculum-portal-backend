export const numberText=(n,digits=0)=>typeof n==='number'&&Number.isFinite(n)?n.toLocaleString('vi-VN',{minimumFractionDigits:digits,maximumFractionDigits:digits}):'—';
export const semesterText=code=>/^20\d{2}-[123]$/.test(code)?`${code.endsWith('3')?'Học kỳ hè':'Học kỳ '+code.slice(-1)} · ${code.slice(0,4)}–${Number(code.slice(0,4))+1}`:'Chưa xác định học kỳ';
export function highestAttempts(rows){
  const best=new Map();
  for(const row of rows){const old=best.get(row.courseCode);
    const nonNumericStatus=row.score10==null&&old?.score10==null&&row.status!==old?.status;
    if(!old || (nonNumericStatus?row.status==='PASSED':(row.score10??-1)>(old.score10??-1)
      || (row.score10===old.score10 && (row.completedAt??'')>(old.completedAt??''))))best.set(row.courseCode,row);
  }
  return [...best.values()];
}

export const isPlannedTerm=key=>/^PLAN:\d+$/.test(key);
export const termLabel=group=>group.planned?`Học kỳ dự kiến ${group.plannedSemesterNumber}`:semesterText(group.semesterCode);
export function transcriptPlanState(data){
  const plan=data?.curriculumPlan;
  if(!plan)return {problem:true,message:'Máy chủ đang trả bảng điểm theo cấu trúc cũ, thiếu kế hoạch học phần. Vui lòng liên hệ quản trị viên để cập nhật máy chủ. Lịch sử điểm vẫn được giữ nguyên.'};
  if(typeof plan.available!=='boolean'||!Array.isArray(plan.semesters)||plan.semesters.some(s=>!s||!isPlannedTerm(s.semesterKey)||!Array.isArray(s.courses)))
    return {problem:true,message:'Phản hồi kế hoạch CTĐT không đúng cấu trúc: thiếu học kỳ dự kiến hoặc danh sách học phần. Vui lòng báo quản trị viên kiểm tra dữ liệu bảng điểm.'};
  if(!plan.available)return {problem:true,message:plan.message||'Không thể đọc cây chuyên ngành đã gán. Vui lòng liên hệ giáo vụ kiểm tra ngành, khóa, chuyên ngành và phiên bản CTĐT trong hồ sơ.'};
  if(!plan.semesters.length)return {problem:true,message:'Cây CTĐT đã trả về nhưng chưa có học kỳ dự kiến. Giáo vụ cần kiểm tra cấu trúc cây chuyên ngành đã gán.'};
  return {problem:false,message:plan.message||'Học phần theo đúng cây chuyên ngành và phiên bản CTĐT đã gán.'};
}
export function selectTranscript(data,{search='',year='all',term='all',mode='attempts'}={}){
  const all=data?.attempts??[],query=search.trim().toLocaleLowerCase('vi');
  const matches=r=>`${r.courseCode??''} ${r.courseName??''}`.toLocaleLowerCase('vi').includes(query);
  const plans=!transcriptPlanState(data).problem?data.curriculumPlan.semesters:[];
  const calendar=s=>/^20\d{2}-[123]$/.test(s.academicSemesterCode??'')?s.academicSemesterCode:null;
  const plannedByCode=new Map(),historyByCode=new Map();
  for(const s of plans)for(const c of s.courses)plannedByCode.set(c.courseCode,{...c,plannedSemesterNumber:s.plannedSemesterNumber});
  for(const a of all){if(!historyByCode.has(a.courseCode))historyByCode.set(a.courseCode,[]);historyByCode.get(a.courseCode).push(a)}
  const base=(mode==='highest'?highestAttempts(all):all).map((a,i)=>({...plannedByCode.get(a.courseCode),...a,planned:false,hasActualResult:true,rowKey:a.recordId+':'+(a.attemptId??i)}));
  const periods=new Map((data?.semesters??[]).map(s=>[s.semesterCode,{...s,plannedCourseCount:null,resultCourseCount:new Set(all.filter(a=>a.semesterCode===s.semesterCode).map(a=>a.courseCode)).size,rows:base.filter(a=>a.semesterCode===s.semesterCode)}]));
  const planGroups=[];
  for(const s of plans){
    const code=calendar(s),unmapped={...s,semesterCode:s.semesterKey,academicYear:null,planned:true,gpa4:null,gpaCredits:0,attemptedCredits:0,earnedCredits:0,attemptCount:0,
      plannedCourseCount:s.courses.length,resultCourseCount:s.courses.filter(c=>historyByCode.has(c.courseCode)).length,rows:[]};
    let group=unmapped;
    if(code){
      if(!periods.has(code))periods.set(code,{semesterCode:code,academicYear:code.slice(0,4),term:Number(code.slice(-1)),gpa4:null,gpaCredits:0,attemptedCredits:0,earnedCredits:0,attemptCount:0,resultCourseCount:0,rows:[]});
      group=periods.get(code);group.plannedCourseCount=(group.plannedCourseCount??0)+s.courses.length;
    }
    for(const c of s.courses){
      const history=historyByCode.get(c.courseCode)??[];
      // Existing attempts remain exclusively in their actual period, also in highest-score mode.
      if(code?history.some(a=>a.semesterCode===code):history.length)continue;
      group.rows.push({...c,planned:true,hasActualResult:false,plannedSemesterNumber:s.plannedSemesterNumber,semesterCode:code??s.semesterKey,
        state:history.length?'HAS_HISTORY_ELSEWHERE':c.state,passedElsewhere:history.some(a=>a.status==='PASSED'),
        actualSemesterCodes:[...new Set(history.map(a=>a.semesterCode))],rowKey:s.semesterKey+':'+c.courseCode});
    }
    if(!code)planGroups.push(unmapped);
  }
  const actual=[...periods.values()].sort((a,b)=>a.semesterCode==='UNKNOWN'?1:b.semesterCode==='UNKNOWN'?-1:a.semesterCode.localeCompare(b.semesterCode));
  const unscoped=[...actual,...planGroups.sort((a,b)=>a.plannedSemesterNumber-b.plannedSemesterNumber)];
  const selectedPlan=plans.find(s=>s.semesterKey===term),planCodes=selectedPlan?new Set(selectedPlan.courses.map(c=>c.courseCode)):null;
  const yearMatches=g=>year==='all'||(g.planned?year==='planned':g.academicYear===year);
  const termMatches=g=>term==='all'||(planCodes?g.semesterCode===term||all.some(a=>a.semesterCode===g.semesterCode&&planCodes.has(a.courseCode))||calendar(selectedPlan)===g.semesterCode:g.semesterCode===term);
  const rows=base.filter(a=>(year==='all'||a.academicYear===year)&&(term==='all'||(planCodes?planCodes.has(a.courseCode):a.semesterCode===term))&&matches(a));
  const groups=unscoped.filter(yearMatches).filter(termMatches).filter(g=>!g.planned||g.rows.length||!g.courses.length||g.semesterCode===term)
    .map(g=>({...g,rows:g.rows.filter(r=>(!planCodes||planCodes.has(r.courseCode))&&matches(r))}))
    .filter(g=>!query||g.rows.length||term!=='all'||year!=='all');
  const selectedSemester=term!=='all'?(periods.get(term)??(selectedPlan&&calendar(selectedPlan)?periods.get(calendar(selectedPlan)):planGroups.find(g=>g.semesterCode===term))):null;
  const issues=(data?.issues??[]).filter(i=>(!i.semesterCode||term==='all'||i.semesterCode===term||planCodes?.has(i.courseCode))
    &&(!i.courseCode||rows.some(r=>r.courseCode===i.courseCode)||groups.some(g=>g.rows.some(r=>r.courseCode===i.courseCode))
      ||(!all.some(r=>r.courseCode===i.courseCode)&&(!query||i.courseCode.toLocaleLowerCase('vi').includes(query)))));
  return {groups,rows,issues,selectedSemester,failedCourses:highestAttempts(rows).filter(r=>r.status==='FAILED').length,
    years:[...new Set(actual.map(s=>s.academicYear).filter(Boolean))].sort(),hasPlan:plans.length>0,
    terms:[...actual,...planGroups].filter(yearMatches)};
}
