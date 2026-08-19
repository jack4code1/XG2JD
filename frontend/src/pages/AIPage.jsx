import { useState } from 'react'
import client from '../api/client'

const EXAMPLES = ['帮我策划双11秒杀', '分析系统数据', '生成新用户优惠券文案']

export default function AIPage() {
  const [query, setQuery] = useState('')
  const [plan, setPlan] = useState('')
  const [loading, setLoading] = useState(false)
  const [executing, setExecuting] = useState(false)
  const [execMsg, setExecMsg] = useState('')

  const submit = async (e) => {
    e?.preventDefault(); if(!query.trim()) return
    setLoading(true); setPlan(''); setExecMsg('')
    try { const { data } = await client.post('/ai/campaign/plan', { query }); setPlan(data.plan) }
    catch { setPlan('AI服务不可用') }
    setLoading(false)
  }

  const execute = async () => {
    setExecuting(true); setExecMsg('')
    try {
      const { data } = await client.post('/ai/campaign/execute', { query })
      setExecMsg(data.success ? `✅ 已创建: ${data.couponName} (ID:${data.couponId})` : `❌ ${data.message}`)
    } catch { setExecMsg('❌ 执行失败') }
    setExecuting(false)
  }

  return (
    <div>
      <h2 className="text-2xl font-bold mb-6">🤖 AI 运营团队</h2>
      <div className="card bg-base-100 shadow-lg mb-6">
        <div className="card-body">
          <div className="flex gap-3">
            <input value={query} onChange={e=>setQuery(e.target.value)} onKeyDown={e=>e.key==='Enter'&&submit(e)}
              placeholder="描述需求..." className="input input-bordered flex-1" />
            <button onClick={submit} disabled={loading} className="btn btn-primary">{loading?'...':'🚀 策划'}</button>
          </div>
          <div className="flex gap-2 mt-2">{EXAMPLES.map((ex,i)=><button key={i} onClick={()=>{setQuery(ex)}} className="btn btn-xs btn-outline">{ex}</button>)}</div>
        </div>
      </div>
      {plan && <div className="card bg-base-100 shadow-lg"><div className="card-body">
        <div className="flex justify-between mb-4">
          <span className="text-sm opacity-60">4Agent并行分析</span>
          <div className="flex gap-2">
            <button onClick={()=>navigator.clipboard.writeText(plan)} className="btn btn-xs btn-outline">📋</button>
            <button onClick={execute} disabled={executing} className="btn btn-xs btn-primary">{executing?'...':'🚀 一键创建'}</button>
          </div>
        </div>
        <pre className="whitespace-pre-wrap text-sm opacity-80">{plan}</pre>
        {execMsg && <div className={`alert ${execMsg.startsWith('✅')?'alert-success':'alert-error'} mt-3`}>{execMsg}</div>}
      </div></div>}
    </div>
  )
}