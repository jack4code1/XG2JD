import { useState } from 'react'
import { motion } from 'framer-motion'
import GlassCard from '../components/GlassCard'
import client from '../api/client'

export default function MerchantTab({ auth }) {
  const { user } = auth
  const [form, setForm] = useState({ couponName: '', totalStock: 100, perUserMax: 1, endHours: 24 })
  const [created, setCreated] = useState(null)
  const [drillStatus, setDrillStatus] = useState(null)
  const [drillLoading, setDrillLoading] = useState(false)

  if (!user) {
    return <GlassCard className="max-w-sm mx-auto mt-20 text-center py-8"><div className="text-4xl mb-3">🔒</div><p className="text-gray-400">请先登录商家账号</p></GlassCard>
  }

  const handleCreate = async (e) => {
    e.preventDefault()
    try {
      const now = new Date(), end = new Date(now.getTime() + form.endHours * 3600000)
      const { data } = await client.post('/coupon/create', {
        couponName: form.couponName, totalStock: Number(form.totalStock),
        remainStock: Number(form.totalStock), startTime: now.toISOString(),
        endTime: end.toISOString(), perUserMax: Number(form.perUserMax), status: 1
      })
      setCreated(data)
    } catch (e) { alert('创建失败: ' + (e.response?.data?.message || e.message)) }
  }

  const handleDrill = async (scenario) => {
    setDrillLoading(true)
    try {
      const { data: start } = await client.post(`/drill/start?scenario=${scenario}`)
      setDrillStatus({ ...start, phase: 'inject' })
      await new Promise(r => setTimeout(r, 3000))
      const { data: recover } = await client.post('/drill/recover')
      setDrillStatus({ ...recover, phase: 'recover' })
    } catch (e) { setDrillStatus({ scenario, phase: 'error', message: e.message }) }
    setDrillLoading(false)
  }

  return (
    <div className="space-y-6">
      <GlassCard>
        <h2 className="text-lg font-bold mb-4 gradient-text">📦 创建优惠券活动</h2>
        <form onSubmit={handleCreate} className="space-y-4">
          <input value={form.couponName} onChange={e => setForm({...form, couponName: e.target.value})}
            placeholder="优惠券名称" required className="w-full bg-white/5 border border-white/10 rounded-xl px-4 py-3 text-white placeholder-gray-500 focus:outline-none focus:border-purple-500" />
          <div className="grid grid-cols-3 gap-4">
            <div><label className="text-xs text-gray-500 mb-1 block">总库存</label>
              <input type="number" value={form.totalStock} onChange={e => setForm({...form, totalStock: e.target.value})}
                className="w-full bg-white/5 border border-white/10 rounded-xl px-4 py-3 text-white focus:outline-none focus:border-purple-500" /></div>
            <div><label className="text-xs text-gray-500 mb-1 block">每人限购</label>
              <input type="number" value={form.perUserMax} onChange={e => setForm({...form, perUserMax: e.target.value})}
                className="w-full bg-white/5 border border-white/10 rounded-xl px-4 py-3 text-white focus:outline-none focus:border-purple-500" /></div>
            <div><label className="text-xs text-gray-500 mb-1 block">时长(小时)</label>
              <input type="number" value={form.endHours} onChange={e => setForm({...form, endHours: e.target.value})}
                className="w-full bg-white/5 border border-white/10 rounded-xl px-4 py-3 text-white focus:outline-none focus:border-purple-500" /></div>
          </div>
          <button type="submit" className="w-full gradient-btn text-white py-3 rounded-xl font-medium">创建活动 + 预热Redis</button>
        </form>
        {created && (
          <motion.div initial={{ opacity:0 }} animate={{ opacity:1 }}
            className="mt-4 p-3 rounded-xl bg-green-500/10 border border-green-500/20 text-sm text-green-400">
            ✅ 已创建: {created.couponName} (ID:{created.id}) · 库存{created.totalStock} · 已预热
          </motion.div>
        )}
      </GlassCard>

      <GlassCard>
        <h2 className="text-lg font-bold mb-4 gradient-text">⚡ 故障演练</h2>
        <div className="grid grid-cols-2 gap-3">
          {[{ key: 'redis', label: '🔴 Redis宕机' },{ key: 'mq', label: '🟡 MQ积压' },
            { key: 'db', label: '🟠 DB慢查询' },{ key: 'network', label: '🔵 网络延迟' }].map(s => (
            <button key={s.key} onClick={() => handleDrill(s.key)} disabled={drillLoading}
              className="glass glass-hover rounded-xl p-4 text-left disabled:opacity-50">
              <div className="text-sm font-medium">{s.label}</div>
            </button>
          ))}
        </div>
      </GlassCard>
    </div>
  )
}