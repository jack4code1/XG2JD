import { useEffect, useState } from 'react'
import client from '../api/client'

const EXAMPLES = [
  '创建一个新客优惠券活动',
  '给优惠券 1 追加 100 张库存',
  '暂停优惠券 1',
  '恢复优惠券 1',
]

const ACTION_LABEL = {
  CREATE_CAMPAIGN: '创建并发布活动',
  INCREASE_STOCK: '追加活动库存',
  PAUSE_CAMPAIGN: '暂停活动',
  RESUME_CAMPAIGN: '恢复活动',
}

const STATUS_LABEL = {
  WAITING_CONFIRMATION: '等待确认',
  EXECUTING: '执行中',
  COMPLETED: '执行完成',
  FAILED: '执行失败',
  CANCELED: '已取消',
}

export default function AIPage() {
  const [query, setQuery] = useState('')
  const [tasks, setTasks] = useState([])
  const [current, setCurrent] = useState(null)
  const [planning, setPlanning] = useState(false)
  const [executing, setExecuting] = useState(false)
  const [message, setMessage] = useState('')

  const loadTasks = async (selectTaskNo) => {
    const { data } = await client.get('/ai/tasks')
    const list = Array.isArray(data) ? data : []
    setTasks(list)
    const selected = selectTaskNo ? list.find(item => item.taskNo === selectTaskNo) : null
    setCurrent(selected || list[0] || null)
  }

  useEffect(() => { loadTasks().catch(() => {}) }, [])

  const plan = async () => {
    if (!query.trim()) return
    setPlanning(true)
    setMessage('')
    try {
      const { data } = await client.post('/ai/tasks', { query })
      setCurrent(data)
      await loadTasks(data.taskNo)
      setMessage('AI 已完成工具选择和参数规划，请确认后执行。')
    } catch (error) {
      setMessage(error.response?.data?.message || '任务规划失败')
    } finally {
      setPlanning(false)
    }
  }

  const confirm = async () => {
    if (!current) return
    setExecuting(true)
    setMessage('')
    try {
      const { data } = await client.post(`/ai/tasks/${current.taskNo}/confirm`)
      setCurrent(data)
      await loadTasks(data.taskNo)
      setMessage(data.status === 'COMPLETED' ? '任务已真实执行，业务数据和 Redis 已更新。' : data.result?.message || '任务执行失败')
    } catch (error) {
      setMessage(error.response?.data?.message || '任务执行失败')
    } finally {
      setExecuting(false)
    }
  }

  const cancel = async () => {
    if (!current) return
    setExecuting(true)
    try {
      const { data } = await client.post(`/ai/tasks/${current.taskNo}/cancel`)
      setCurrent(data)
      await loadTasks(data.taskNo)
      setMessage('任务已取消，没有执行任何写操作。')
    } catch (error) {
      setMessage(error.response?.data?.message || '取消失败')
    } finally {
      setExecuting(false)
    }
  }

  const proposal = current?.proposal || {}
  const result = current?.result || {}

  return <>
    <div className="section-head">
      <div>
        <div className="eyebrow page-eyebrow">AI EXECUTION AGENT</div>
        <h2>AI 运营执行台</h2>
        <div className="muted">理解目标、生成受控任务，并通过业务工具真正执行</div>
      </div>
      <span className="tool-tag">Human-in-the-loop · Auditable</span>
    </div>

    <div className="panel ai-query">
      <div className="ai-query-row">
        <input className="input" value={query} onChange={event => setQuery(event.target.value)}
          onKeyDown={event => event.key === 'Enter' && plan()}
          placeholder="例如：给优惠券 1 追加 100 张库存" />
        <button className="primary-btn" onClick={plan} disabled={planning}>{planning ? '规划中…' : '创建执行任务'}</button>
      </div>
      <div className="filters ai-examples">{EXAMPLES.map(example =>
        <button className="filter" key={example} onClick={() => setQuery(example)}>{example}</button>)}</div>
    </div>

    {message && <div className={'panel ai-message ' + (message.includes('失败') ? 'error' : 'success')}>{message}</div>}

    <div className="execution-layout">
      <aside className="panel task-sidebar">
        <div className="task-sidebar-head"><h3>最近任务</h3><button className="order-detail-btn" onClick={() => loadTasks(current?.taskNo)}>刷新</button></div>
        {tasks.length ? tasks.map(task => <button key={task.taskNo}
          className={'task-item ' + (current?.taskNo === task.taskNo ? 'active' : '')}
          onClick={() => setCurrent(task)}>
          <span>{ACTION_LABEL[task.actionType] || task.actionType}</span>
          <small>{task.query}</small>
          <b className={'task-status status-' + task.status.toLowerCase()}>{STATUS_LABEL[task.status] || task.status}</b>
        </button>) : <div className="task-empty">还没有执行任务</div>}
      </aside>

      <main className="panel execution-main">
        {current ? <>
          <div className="execution-heading">
            <div><span className="eyebrow">IMMUTABLE PROPOSAL</span><h3>{ACTION_LABEL[current.actionType] || current.actionType}</h3></div>
            <span className={'task-status status-' + current.status.toLowerCase()}>{STATUS_LABEL[current.status] || current.status}</span>
          </div>
          <p className="execution-query">“{current.query}”</p>

          <div className="proposal-summary"><strong>{proposal.summary}</strong><span>任务号 {current.taskNo?.slice(0, 8)}</span></div>
          <ProposalGrid proposal={proposal} />

          {(proposal.guardrails || []).length > 0 && <div className="guardrail-box"><strong>执行护栏</strong><div>{proposal.guardrails.map(item => <span key={item}>{item}</span>)}</div></div>}

          {current.status === 'WAITING_CONFIRMATION' && <div className="confirmation-box">
            <div><strong>需要商户确认</strong><p>确认后严格执行上方已保存参数，不会再次调用模型改变方案。</p></div>
            <div className="confirmation-actions"><button className="logout" onClick={cancel} disabled={executing}>取消任务</button><button className="primary-btn" onClick={confirm} disabled={executing}>{executing ? '执行中…' : '确认并执行'}</button></div>
          </div>}

          {current.status === 'COMPLETED' && <div className="execution-result"><strong>执行回执</strong><ResultView result={result} /></div>}
          {current.status === 'FAILED' && <div className="execution-result failed"><strong>执行失败</strong><p>{result.message || '工具调用失败，请查看动作日志。'}</p></div>}

          <div className="timeline"><h3>动作时间线</h3>{(current.actions || []).map((action, index) =>
            <div className="timeline-item" key={index}><i /><div><strong>{ACTION_LABEL[action.actionType] || action.actionType}</strong><span>{STATUS_LABEL[action.status] || action.status}</span>{action.errorMessage && <small>{action.errorMessage}</small>}</div></div>)}</div>
        </> : <div className="execution-empty"><b>输入一个运营目标</b><span>AI 会把它转换成可审计、可确认、可执行的业务任务。</span></div>}
      </main>
    </div>
  </>
}

function ProposalGrid({ proposal }) {
  const fields = [
    ['活动名称', proposal.couponName], ['优惠券 ID', proposal.couponId],
    ['优惠金额', proposal.discountAmount != null ? `¥${proposal.discountAmount}` : null],
    ['库存', proposal.stock ?? proposal.amount], ['有效时间', proposal.durationHours ? `${proposal.durationHours} 小时` : null],
    ['每人限领', proposal.perUserMax ? `${proposal.perUserMax} 张` : null],
    ['当前库存', proposal.currentRemain], ['执行后库存', proposal.afterRemain],
    ['目标状态', proposal.targetStatus === 3 ? '暂停' : proposal.targetStatus === 1 ? '运行中' : null],
  ].filter(([, value]) => value !== undefined && value !== null)
  return <div className="proposal-grid">{fields.map(([label, value]) => <div key={label}><span>{label}</span><strong>{value}</strong></div>)}</div>
}

function ResultView({ result }) {
  return <div className="result-receipt">
    {Object.entries(result).filter(([key]) => !['success', 'tool'].includes(key)).map(([key, value]) =>
      <div key={key}><span>{key}</span><b>{String(value)}</b></div>)}
  </div>
}
