import { useState, useEffect, useCallback } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import GlassCard from '../components/GlassCard'
import Confetti from '../components/Confetti'
import client from '../api/client'

const ICONS = { '餐饮': '🍔', '服饰': '👗', '数码': '📱', '美妆': '💄', '其他': '🛒' }

export default function UserTab({ auth }) {
  const { user, loginSuccess, logout } = auth
  const [username, setUsername] = useState(''); const [password, setPassword] = useState('')
  const [role, setRole] = useState('USER')
  const [merchants, setMerchants] = useState([])
  const [selectedMerchant, setSelectedMerchant] = useState(null)
  const [result, setResult] = useState(null); const [showConfetti, setShowConfetti] = useState(false)
  const [loading, setLoading] = useState(false)

  const loadMerchants = useCallback(async () => {
    try { const { data } = await client.get('/merchant/list'); setMerchants(Array.isArray(data) ? data : []) }
    catch { setMerchants([]) }
  }, [])

  useEffect(() => { if (user && user.role === 'USER') loadMerchants() }, [user, loadMerchants])

  const handleLogin = async (e) => {
    e.preventDefault()
    try {
      const { data } = await client.post('/auth/register', { username, password, role })
      if (!data.success) { alert(data.message); return }
      const res = await client.post('/auth/login', { username, password })
      if (!res.data.accessToken) throw new Error('no token')
      localStorage.setItem('accessToken', res.data.accessToken)
      localStorage.setItem('refreshToken', res.data.refreshToken)
      localStorage.setItem('user', JSON.stringify({ username, role }))
      loginSuccess()
    } catch { alert('登录失败') }
  }

  const handleLoginOnly = async (e) => {
    e.preventDefault()
    try {
      const res = await client.post('/auth/login', { username, password })
      if (!res.data.accessToken) throw new Error('no token')
      localStorage.setItem('accessToken', res.data.accessToken)
      localStorage.setItem('refreshToken', res.data.refreshToken)
      localStorage.setItem('user', JSON.stringify({ username, role }))
      loginSuccess()
    } catch { alert('用户名或密码错误') }
  }

  const handleSeckill = async (couponId) => {
    setLoading(true); const t0 = performance.now()
    try {
      const fp = 'fp-' + Math.random().toString(36).slice(2, 10)
      const { data } = await client.post('/seckill/execute', { couponId, deviceFingerprint: fp })
      setResult({ ...data, elapsed: Math.round(performance.now() - t0) })
      if (data.success) { setShowConfetti(true); setTimeout(() => setShowConfetti(false), 2500) }
    } catch (e) { setResult({ success: false, message: '系统繁忙', elapsed: Math.round(performance.now() - t0) }) }
    setLoading(false)
  }

  if (!user) {
    return (
      <GlassCard className="max-w-sm mx-auto mt-10">
        <h2 className="text-xl font-semibold mb-6 text-center gradient-text">秒杀商城 · 登录/注册</h2>
        <div className="flex gap-2 mb-4">
          {['USER', 'MERCHANT'].map(r => (
            <button key={r} onClick={() => setRole(r)}
              className={`flex-1 py-2 rounded-xl text-sm font-medium transition-all ${role === r ? 'gradient-btn text-white' : 'glass text-gray-400'}`}>
              {r === 'USER' ? '👤 普通用户' : '🏪 商家'}
            </button>
          ))}
        </div>
        <form onSubmit={handleLogin} className="space-y-4">
          <input value={username} onChange={e => setUsername(e.target.value)} placeholder="用户名" required
            className="w-full bg-white/5 border border-white/10 rounded-xl px-4 py-3 text-white placeholder-gray-500 focus:outline-none focus:border-purple-500" />
          <input value={password} onChange={e => setPassword(e.target.value)} placeholder="密码" type="password" required
            className="w-full bg-white/5 border border-white/10 rounded-xl px-4 py-3 text-white placeholder-gray-500 focus:outline-none focus:border-purple-500" />
          <div className="flex gap-3">
            <button type="submit" className="flex-1 gradient-btn text-white py-3 rounded-xl font-medium">注册并登录</button>
            <button type="button" onClick={handleLoginOnly} className="flex-1 glass glass-hover text-gray-300 py-3 rounded-xl font-medium">登录</button>
          </div>
        </form>
      </GlassCard>
    )
  }

  // 商户详情页
  if (selectedMerchant) {
    const m = selectedMerchant
    return (
      <div className="space-y-4">
        <Confetti active={showConfetti} />
        <button onClick={() => setSelectedMerchant(null)} className="text-sm text-gray-400 hover:text-white mb-2">← 返回商城</button>
        <GlassCard>
          <div className="flex items-center gap-4 mb-4">
            <span className="text-4xl">{ICONS[m.category] || '🛒'}</span>
            <div>
              <h2 className="text-xl font-bold">{m.shopName}</h2>
              <p className="text-xs text-gray-500">{m.shopDesc || m.category} · {m.couponCount}张优惠券</p>
            </div>
          </div>
        </GlassCard>
        <div className="grid gap-3">
          {(m.coupons || []).map(c => {
            const remain = c.remainStock || 0, total = c.totalStock || 100, pct = (remain / total) * 100
            const active = new Date(c.endTime).getTime() > Date.now()
            return (
              <GlassCard key={c.id} className="glass-hover">
                <div className="flex items-center justify-between">
                  <div>
                    <h3 className="font-bold">{c.couponName}</h3>
                    <p className="text-xs text-gray-500">剩余 {remain}/{total} · {active ? '进行中' : '已结束'}</p>
                  </div>
                  <button onClick={() => handleSeckill(c.id)} disabled={!active || loading}
                    className={`px-6 py-2 rounded-xl text-sm font-bold ${active ? 'gradient-btn text-white' : 'bg-white/5 text-gray-600'}`}>
                    {loading ? '...' : active ? '⚡抢' : '结束'}
                  </button>
                </div>
              </GlassCard>
            )
          })}
        </div>
        <AnimatePresence>
          {result && (
            <motion.div initial={{ opacity: 0, scale: 0.9 }} animate={{ opacity: 1, scale: 1 }} exit={{ opacity: 0 }}
              className="fixed inset-0 flex items-center justify-center z-40 bg-black/60 backdrop-blur-sm" onClick={() => setResult(null)}>
              <div className="glass rounded-2xl p-8 max-w-sm w-full mx-4" onClick={e => e.stopPropagation()}>
                <div className="text-center">
                  <div className="text-5xl mb-3">{result.success ? '🎉' : '😞'}</div>
                  <h3 className={`text-lg font-bold ${result.success ? 'text-green-400' : 'text-red-400'}`}>{result.success ? '抢券成功！' : '抢券失败'}</h3>
                  <p className="text-gray-400 text-sm my-2">{result.message}</p>
                  {result.success && <div className="text-xs text-gray-500 space-y-1"><p>权重: {result.userWeight}</p><p>耗时: {result.elapsed}ms</p></div>}
                  <button onClick={() => setResult(null)} className="gradient-btn text-white px-6 py-2 rounded-xl text-sm mt-3">确定</button>
                </div>
              </div>
            </motion.div>
          )}
        </AnimatePresence>
      </div>
    )
  }

  // 商城首页
  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <span className="text-sm text-gray-400">👤 {user.username}</span>
        <button onClick={logout} className="text-xs text-gray-500 hover:text-red-400">退出</button>
      </div>
      <h2 className="text-lg font-bold">🏬 商家列表</h2>
      <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
        {merchants.map(m => (
          <motion.div key={m.id} whileHover={{ scale: 1.02 }} onClick={() => setSelectedMerchant(m)}
            className="glass glass-hover rounded-2xl p-5 cursor-pointer transition-all">
            <div className="text-4xl mb-3 text-center">{ICONS[m.category] || '🛒'}</div>
            <h3 className="font-bold text-sm text-center">{m.shopName}</h3>
            <p className="text-xs text-gray-500 text-center mt-1">{m.couponCount}张优惠券</p>
          </motion.div>
        ))}
        {merchants.length === 0 && <GlassCard className="col-span-full text-center py-8 text-gray-500">暂无商家入驻，请先注册商家账号</GlassCard>}
      </div>
    </div>
  )
}