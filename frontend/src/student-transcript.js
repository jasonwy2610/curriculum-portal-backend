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
export function selectTranscript(data,{search='',year='all',term='all',mode='attempts'}={}){
  const all=data?.attempts??[], query=search.trim().toLocaleLowerCase('vi');
  const base=mode==='highest'?highestAttempts(all):all;
  const rows=base.filter(r=>(year==='all'||r.academicYear===year)&&(term==='all'||r.semesterCode===term)
    &&`${r.courseCode??''} ${r.courseName??''}`.toLocaleLowerCase('vi').includes(query));
  const groups=(data?.semesters??[]).map(s=>({...s,rows:rows.filter(r=>r.semesterCode===s.semesterCode)}))
    .filter(s=>s.rows.length).sort((a,b)=>b.semesterCode.localeCompare(a.semesterCode));
  const contextual=all.filter(r=>(year==='all'||r.academicYear===year)&&(term==='all'||r.semesterCode===term)
    &&`${r.courseCode??''} ${r.courseName??''}`.toLocaleLowerCase('vi').includes(query));
  const selectedSemester=term!=='all'?(data?.semesters??[]).find(s=>s.semesterCode===term):null;
  const issues=(data?.issues??[]).filter(i=>(!i.semesterCode||term==='all'||i.semesterCode===term)
    &&(!i.courseCode||contextual.some(r=>r.courseCode===i.courseCode)
      ||(!all.some(r=>r.courseCode===i.courseCode)&&(!query||i.courseCode.toLocaleLowerCase('vi').includes(query)))));
  return {groups,rows,issues,selectedSemester,failedCourses:highestAttempts(rows).filter(r=>r.status==='FAILED').length,
    years:[...new Set(all.map(r=>r.academicYear).filter(Boolean))].sort().reverse(),
    terms:(data?.semesters??[]).filter(s=>year==='all'||s.academicYear===year).sort((a,b)=>b.semesterCode.localeCompare(a.semesterCode))};
}
