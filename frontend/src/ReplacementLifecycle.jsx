import React, {useEffect, useMemo, useState} from 'react';
import {isPublished, ruleFamily, ruleStatuses, ruleStatus, ruleWindows,
  vietnamDateTimeIso} from './replacement-rules.js';

function ruleDateTime(value) {
  return value && Number.isFinite(new Date(value).getTime())
    ? new Date(value).toLocaleString('vi-VN', {timeZone: 'Asia/Ho_Chi_Minh'}) : '—';
}

export default function ReplacementLifecycle({rules, id, setId, token, notify, refresh, refreshing, onEdit, request, Modal, Field, Status, now}) {
  const [history, setHistory] = useState([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyError, setHistoryError] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [validation, setValidation] = useState(null);
  const [retirement, setRetirement] = useState(null);
  const current = rules.find(rule => rule.id === id);
  const windows = useMemo(() => ruleWindows(rules), [rules]);
  const status = current && ruleStatus(current, windows, now);
  const pending = busy || refreshing;
  const path = id && '/api/v1/admin/replacement-rules/' + encodeURIComponent(id);
  const published = current && isPublished(current);
  const family = current ? rules.filter(rule => ruleFamily(rule) === ruleFamily(current)) : [];
  const canRevise = published && family.every(rule => rule.lifecycleStatus !== 'DRAFT'
    && (rule.versionNumber ?? 1) <= (current.versionNumber ?? 1));
  const canRetire = published && ['SCHEDULED', 'ACTIVE'].includes(status) && !current.endsAt;

  useEffect(() => {
    let live = true;
    setHistory([]);
    setHistoryError('');
    setHistoryLoading(Boolean(path));
    setValidation(null);
    if (path) request(path + '/history', {token}).then(data => {
      if (live) setHistory(Array.isArray(data) ? data : []);
    }).catch(error => {
      if (live) setHistoryError(error.message);
    }).finally(() => {
      if (live) setHistoryLoading(false);
    });
    return () => { live = false; };
  }, [path, token, rules, request]);

  async function action(kind, body) {
    setBusy(true);
    setError('');
    try {
      if (kind === 'validation') {
        const preview = await request(path + '/validation', {token});
        setValidation({rule: current, effectiveDate: preview.effectiveDate});
        return;
      }
      const changed = await request(path + '/' + kind, {token, method: 'POST', body});
      setRetirement(null);
      setValidation(null);
      setId(changed.id);
      notify({publish: 'Đã công bố quy tắc', revisions: 'Đã tạo phiên bản nháp',
        retire: 'Đã ghi nhận thời điểm kết thúc'}[kind]);
      await refresh();
    } catch (error) {
      if (kind === 'publish' || kind === 'validation') setValidation(null);
      setError(error.message);
    } finally {
      setBusy(false);
    }
  }

  function select(value) {
    setError('');
    setRetirement(null);
    setId(value);
  }

  function confirmRetirement(event) {
    event.preventDefault();
    const endsAt = vietnamDateTimeIso(retirement.endsAt);
    if (retirement.endsAt && (!endsAt || Date.parse(endsAt) <= Date.now())) {
      setError('Chọn ngày giờ kết thúc trong tương lai hoặc để trống để kết thúc ngay.');
      return;
    }
    if (window.confirm(endsAt ? 'Xác nhận lên lịch kết thúc phiên bản này?' : 'Xác nhận ngừng áp dụng phiên bản này ngay?')) {
      action('retire', {endsAt, reason: retirement.reason.trim() || null});
    }
  }

  return <section className="panel huf-feature-panel">
    <h3>Vòng đời quy tắc thay thế</h3>
    <label className="field-label">Phiên bản
      <select value={id} disabled={pending} onChange={e => select(e.target.value)}>
        <option value="">Chọn phiên bản</option>
        {rules.map(rule => <option key={rule.id} value={rule.id}>
          {(rule.oldCourseCodes ?? []).join(' + ')} → {(rule.newCourseCodes ?? []).join(' + ')} · v{rule.versionNumber ?? 1} · {ruleStatuses[ruleStatus(rule, windows, now)]}
        </option>)}
      </select>
    </label>
    {current && <>
      <p><Status value={status}/> · Hiệu lực từ {ruleDateTime(windows.get(id)?.start ?? current.effectiveDate)}
        {' · Kết thúc '}{ruleDateTime(windows.get(id)?.end ?? current.endsAt)}</p>
      {current.retirementReason && <p>Lý do ngừng áp dụng: {current.retirementReason}</p>}
      <div className="huf-inline-actions">
        {status === 'DRAFT' && <>
          <button className="outline" disabled={pending} onClick={() => { setValidation(null); onEdit(current); }}>Sửa bản nháp</button>
          <button className="outline" disabled={pending} onClick={() => action('validation')}>Kiểm tra trước công bố</button>
          <button className="primary" disabled={pending || validation?.rule !== current}
            onClick={() => { if (window.confirm('Công bố phiên bản quy tắc này?')) action('publish'); }}>Công bố</button>
        </>}
        {canRevise && <button className="outline" disabled={pending}
          onClick={() => { if (window.confirm('Tạo phiên bản nháp từ quy tắc này?')) action('revisions'); }}>Tạo phiên bản mới</button>}
        {canRetire && <button className="outline" disabled={pending}
          onClick={() => { setError(''); setRetirement({endsAt: '', reason: ''}); }}>Ngừng áp dụng</button>}
      </div>
      {validation?.rule === current && <p role="status">Bản nháp hợp lệ. Dự kiến hiệu lực từ {ruleDateTime(validation.effectiveDate)}.</p>}
      <h4>Lịch sử phiên bản</h4>
      {historyLoading && <p role="status">Đang tải lịch sử…</p>}
      {historyError && <p className="form-error" role="alert">{historyError}</p>}
      {!!history.length && <div className="table-wrap"><table>
        <thead><tr><th>Phiên bản</th><th>Trạng thái</th><th>Công bố</th><th>Hiệu lực từ</th><th>Kết thúc</th>
          <th>Môn cũ → Môn mới</th><th>Lý do ngừng áp dụng</th></tr></thead>
        <tbody>{history.map(rule => <tr key={rule.id}>
          <td><button className="text-btn" disabled={pending} onClick={() => select(rule.id)}>v{rule.versionNumber ?? 1}</button></td>
          <td><Status value={ruleStatus(rule, windows, now)}/></td>
          <td>{ruleDateTime(rule.publishedAt)}</td>
          <td>{ruleDateTime(windows.get(rule.id)?.start ?? rule.effectiveDate)}</td>
          <td>{ruleDateTime(windows.get(rule.id)?.end ?? rule.endsAt)}</td>
          <td>{(rule.oldCourseCodes ?? []).join(', ')} → {(rule.newCourseCodes ?? []).join(', ')}</td>
          <td>{rule.retirementReason || '—'}</td>
        </tr>)}</tbody>
      </table></div>}
      {!historyLoading && !historyError && !history.length && <p className="muted">Chưa có lịch sử phiên bản.</p>}
    </>}
    {error && !retirement && <p className="form-error" role="alert">{error}</p>}
    {retirement && <Modal title="Ngừng áp dụng quy tắc" submitLabel="Xác nhận ngừng áp dụng" busy={busy}
      onClose={() => { if (!busy) { setRetirement(null); setError(''); } }} onSubmit={confirmRetirement}>
      <p className="field-hint">Để trống ngày giờ kết thúc nếu muốn ngừng ngay.</p>
      <Field label="Ngày giờ kết thúc (giờ Việt Nam)" type="datetime-local" step="1" value={retirement.endsAt}
        set={endsAt => setRetirement({...retirement, endsAt})}/>
      <label className="field-label">Lý do ngừng áp dụng
        <textarea value={retirement.reason} onChange={e => setRetirement({...retirement, reason: e.target.value})}/>
      </label>
      {error && <p className="form-error" role="alert">{error}</p>}
    </Modal>}
  </section>;
}
