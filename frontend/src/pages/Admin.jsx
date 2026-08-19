import { useState } from 'react'
import { useAuth } from '../AuthContext'
import client from '../api/client'

export default function Admin() {
  const { isLogin } = useAuth()
  const [form, setForm] = useState({ couponName:'', totalStock:100, perUserMax:1, endHours:24 })
  const [msg, setMsg] = useState('')

  if (!isLogin) return <div className="text-center py-12"><p>请先登录商家账号</p></div>

  const create = async (e) => {
    e.preventDefault(); setMsg('')
    try {
      const now = new Date(), end = new Date(now.getTime() + form.endHours * 3600000)
      const { data } = await client.post('/coupon/create', {
        couponName: form.couponName, totalStock: Number(form.totalStock),
        remainStock: Number(form.totalStock), startTime: now.toISOString(),
        endTime: end.toISOString(), perUserMax: Number(form.perUserMax), status: 1
      })
      setMsg(`✅ 创建成功！${data.couponName} (ID:${data.id}) 库存${data.totalStock}`)
    } catch(e) { setMsg('❌ '+ (e.response?.data?.message || e.message)) }
  }

  const drill = async (s) => {
    try {
      await client.post(`/drill/start?scenario=${s}`)
      await new Promise(r=>setTimeout(r,3000))
      const { data } = await client.post('/drill/recover')
      alert(`${s} 恢复: ${data.durationMs}ms`)
    } catch(e) { alert('演练失败') }
  }

  return (
    <div>
      <h2 className="text-2xl font-bold mb-6">🏪 商家管理</h2>
      <div className="grid md:grid-cols-2 gap-6">
        <div className="card bg-base-100 shadow-lg">
          <div className="card-body">
            <h3 className="card-title">📦 创建优惠券</h3>
            <form onSubmit={create}>
              <input value={form.couponName} onChange={e=>setForm({...form,couponName:e.target.value})} placeholder="优惠券名称" required className="input input-bordered w-full mb-3" />
              <div className="grid grid-cols-3 gap-3 mb-3">
                <div><label className="label text-xs">总库存</label><input type="number" value={form.totalStock} onChange={e=>setForm({...form,totalStock:e.target.value})} className="input input-bordered w-full" /></div>
                <div><label className="label text-xs">每人限购</label><input type="number" value={form.perUserMax} onChange={e=>setForm({...form,perUserMax:e.target.value})} className="input input-bordered w-full" /></div>
                <div><label className="label text-xs">时长(h)</label><input type="number" value={form.endHours} onChange={e=>setForm({...form,endHours:e.target.value})} className="input input-bordered w-full" /></div>
              </div>
              <button type="submit" className="btn btn-primary w-full">创建活动 + 预热Redis</button>
            </form>
            {msg && <div className={`alert ${msg.startsWith('✅')?'alert-success':'alert-error'} mt-3 py-2 text-sm`}>{msg}</div>}
          </div>
        </div>
        <div className="card bg-base-100 shadow-lg">
          <div className="card-body"><h3 className="card-title">⚡ 故障演练</h3>
            <div className="grid grid-cols-2 gap-2">
              {['redis','mq','db','network'].map(s=><button key={s} onClick={()=>drill(s)} className="btn btn-outline btn-sm">{s==='redis'?'🔴 Redis':s==='mq'?'🟡 MQ':s==='db'?'🟠 DB':'🔵 网络'}</button>)}
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}