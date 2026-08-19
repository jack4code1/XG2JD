import { useState, useRef, useEffect } from 'react'
import { motion } from 'framer-motion'
import GlassCard from '../components/GlassCard'
import client from '../api/client'

const EXAMPLES = ['帮我策划一个双11秒杀活动', '分析当前系统数据并推荐最优策略', '生成一个给新用户的优惠券文案']

export default function AiTab({ auth }) {
  const [query, setQuery] = useState('')
  const [plan, setPlan] = useState('')
  const [loading, setLoading] = useState(false)
  const [elapsed, setElapsed] = useState(0)
  const resultRef = useRef(null)

  useEffect(() => { if (plan && resultRef.current) resultRef.current.scrollIntoView({ behavior: 'smooth' }) }, [plan])

  const handleSubmit = async (e) => {
    e?.preventDefault()
    if (!query.trim() || loading) return
    setLoading(true); setPlan('')
    const t0 = performance.now()
    try {
      const { data } = await client.post('/ai/campaign/plan', { query })
      setPlan(data.plan); setElapsed(Math.round(performance.now() - t0))
    } catch (e) { setPlan('AI服务暂不可用，请确保已设置 DEEPSEEK_API_KEY') }
    setLoading(false)
  }

  return (
    <div className="space-y-6">
      <GlassCard>
        <div className="text-center py-4">
          <div className="text-4xl mb-3">🤖</div>
          <h2 className="text-xl font-bold gradient-text mb-2">AI Multi-Agent 运营团队</h2>
          <p className="text-sm text-gray-500">4个AI Agent并行协作：📊数据分析 · 🛡️风控评估 · ✍️内容生成 · 📈策略推荐</p>
        </div>
      </GlassCard>

      <GlassCard>
        <form onSubmit={handleSubmit} className="flex gap-3">
          <input value={query} onChange={e => setQuery(e.target.value)} placeholder="描述你的活动需求，例如：帮我策划一个双11秒杀..."
            className="flex-1 bg-white/5 border border-white/10 rounded-xl px-4 py-3 text-white placeholder-gray-500 focus:outline-none focus:border-purple-500" />
          <button type="submit" disabled={loading}
            className="gradient-btn text-white px-6 py-3 rounded-xl font-medium disabled:opacity-50 whitespace-nowrap">
            {loading ? '⏳ Agent执行中...' : '🚀 开始策划'}
          </button>
        </form>
        <div className="flex gap-2 mt-3 flex-wrap">
          {EXAMPLES.map((ex, i) => (
            <button key={i} onClick={() => { setQuery(ex) }} className="text-xs glass glass-hover px-3 py-1.5 rounded-lg text-gray-400 hover:text-white">{ex}</button>
          ))}
        </div>
      </GlassCard>

      {loading && (
        <GlassCard>
          <div className="flex items-center justify-center gap-3 py-8">
            {[0,1,2,3].map(i => (
              <motion.div key={i} animate={{ y: [0, -8, 0] }} transition={{ duration: 0.6, repeat: Infinity, delay: i * 0.15 }}
                className="w-2 h-2 rounded-full" style={{ background: ['#6366f1','#10b981','#f59e0b','#8b5cf6'][i] }} />
            ))}
            <span className="text-sm text-gray-500">4个Agent并行分析中...</span>
          </div>
        </GlassCard>
      )}

      {plan && (
        <motion.div ref={resultRef} initial={{ opacity: 0, y: 20 }} animate={{ opacity: 1, y: 0 }}>
          <GlassCard>
            <div className="flex items-center justify-between mb-4">
              <span className="text-xs text-gray-500">⚡ 4Agent并行 · {elapsed}ms</span>
              <button onClick={() => navigator.clipboard.writeText(plan)}
                className="text-xs glass glass-hover px-3 py-1 rounded-lg text-gray-400 hover:text-white">📋 复制</button>
            </div>
            <div className="text-sm whitespace-pre-wrap leading-relaxed text-gray-300 font-mono text-xs">{plan}</div>
          </GlassCard>
        </motion.div>
      )}
    </div>
  )
}