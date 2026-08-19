import { useState, useEffect, useCallback } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import GlassCard from '../components/GlassCard'
import Confetti from '../components/Confetti'
import client from '../api/client'

export default function UserTab({ auth }) {
  const { user, loginSuccess, logout } = auth
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [coupons, setCoupons] = useState([])
  const [orders, setOrders] = useState([])
  const [result, setResult] = useState(null)
  const [showConfetti, setShowConfetti] = useState(false)
  const [loading, setLoading] = useState(false)

  const loadCoupons = useCallback(async () => {
    try { const { data } = await client.get('/coupon/active'); setCoupons(Array.isArray(data) ? data : []) }
    catch { setCoupons([]) }
  }, [])

  const loadOrders = useCallback(async () => {
    if (!user) return
    try {
      const { data } = await client.get('/coupon/active')
      if (data?.length) {
        try { const { data: od } = await client.get(`/order/user/${data[0].id}`); setOrders(Array.isArray(od) ? od : []) }
        catch { setOrders([]) }
      }
    } catch { setOrders([]) }
  }, [user])

  useEffect(() => { if (user) { loadCoupons(); loadOrders() } }, [user, loadCoupons, loadOrders])

  const handleLogin = async (e) => {
    e.preventDefault()
    try {
      const { data } = await client.post('/auth/login', { username, password })
      if (!data.accessToken) throw new Error('no token')
      localStorage.setItem('accessToken', data.accessToken)
      localStorage.setItem('refreshToken', data.refreshToken)
      localStorage.setItem('user', JSON.stringify({ username, expiresIn: data.expiresIn }))
      loginSuccess()
    } catch { alert('用户名或密码错误') }
  }

  const handleRegister = async (e) => {
    e.preventDefault()
    try { const r = await client.post('/auth/register', { username, password }); alert(r.data.message || '注册成功') }
    catch { alert('注册失败') }
  }

  const handleSeckill = async (couponId) => {
    if (!user) return alert('请先登录')
    setLoading(true)
    const t0 = performance.now()
    try {
      const fp = 'fp-' + Math.random().toString(36).slice(2, 10)
      const { data } = await client.post('/seckill/execute', { couponId, deviceFingerprint: fp })
      const elapsed = Math.round(performance.now() - t0)
      setResult({ ...data, elapsed })
      if (data.success) { setShowConfetti(true); setTimeout(() => setShowConfetti(false), 2500); loadCoupons() }
    } catch (e) {
      setResult({ success: false, message: e.response?.data?.message || '系统繁忙', elapsed: Math.round(performance.now() - t0) })
    }
    setLoading(false)
  }

  if (!user) {
    return (
      <GlassCard className="max-w-sm mx-auto mt-20">
        <h2 className="text-xl font-semibold mb-6 text-center gradient-text">秒杀系统 · 登录</h2>
        <form onSubmit={handleLogin} className="space-y-4">
          <input value={username} onChange={e => setUsername(e.target.value)} placeholder="用户名" required
            className="w-full bg-white/5 border border-white/10 rounded-xl px-4 py-3 text-white placeholder-gray-500 focus:outline-none focus:border-purple-500 transition-colors" />
          <input value={password} onChange={e => setPassword(e.target.value)} placeholder="密码" type="password" required
            className="w-full bg-white/5 border border-white/10 rounded-xl px-4 py-3 text-white placeholder-gray-500 focus:outline-none focus:border-purple-500 transition-colors" />
          <div className="flex gap-3">
            <button type="submit" className="flex-1 gradient-btn text-white py-3 rounded-xl font-medium">登录</button>
            <button type="button" onClick={handleRegister} className="flex-1 glass glass-hover text-gray-300 py-3 rounded-xl font-medium">注册</button>
          </div>
        </form>
      </GlassCard>
    )
  }

  return (
    <div className="space-y-6">
      <Confetti active={showConfetti} />
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="w-8 h-8 rounded-full gradient-btn flex items-center justify-center text-sm">{user.username[0].toUpperCase()}</div>
          <span className="text-sm text-gray-300">{user.username}</span>
        </div>
        <button onClick={logout} className="text-xs text-gray-500 hover:text-red-400">退出</button>
      </div>

      <div className="grid gap-4">
        <AnimatePresence>
          {coupons.length === 0 ? (
            <GlassCard><p className="text-gray-500 text-center py-8">暂无进行中的优惠券活动</p></GlassCard>
          ) : coupons.map(c => {
            const remaining = c.remainStock || 0, total = c.totalStock || 100, pct = (remaining / total) * 100
            const isActive = new Date(c.endTime).getTime() > Date.now()
            return (
              <motion.div key={c.id} initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}>
                <GlassCard className="glass-hover">
                  <div className="flex items-start justify-between mb-4">
                    <div><h3 className="text-lg font-bold">{c.couponName || '优惠券'}</h3>
                      <p className="text-xs text-gray-500 mt-1">{isActive ? '⏰ 进行中' : '已结束'} · 每人限{c.perUserMax || 1}张</p></div>
                    <span className={`text-xs px-3 py-1 rounded-full font-medium ${isActive ? 'bg-green-500/10 text-green-400' : 'bg-gray-500/10 text-gray-500'}`}>{isActive ? '进行中' : '已结束'}</span>
                  </div>
                  <div className="mb-4">
                    <div className="flex justify-between text-xs text-gray-500 mb-1"><span>剩余 {remaining} / {total}</span><span>{pct.toFixed(0)}%</span></div>
                    <div className="h-2 bg-white/5 rounded-full overflow-hidden">
                      <motion.div className="h-full rounded-full" style={{ width: `${100 - pct}%`, background: pct > 50 ? 'linear-gradient(90deg, #6366f1, #8b5cf6)' : pct > 20 ? 'linear-gradient(90deg, #f59e0b, #ef4444)' : 'linear-gradient(90deg, #ef4444, #dc2626)' }} initial={{ width: 0 }} animate={{ width: `${100 - pct}%` }} transition={{ duration: 0.8 }} />
                    </div>
                  </div>
                  <button onClick={() => handleSeckill(c.id)} disabled={!isActive || loading}
                    className={`w-full py-3 rounded-xl font-bold text-sm transition-all ${isActive ? 'gradient-btn text-white pulse-glow' : 'bg-white/5 text-gray-600 cursor-not-allowed'}`}>
                    {loading ? '⏳ 秒杀中...' : isActive ? '⚡ 立即抢券' : '活动已结束'}
                  </button>
                </GlassCard>
              </motion.div>
            )
          })}
        </AnimatePresence>
      </div>

      <AnimatePresence>
        {result && (
          <motion.div initial={{ opacity: 0, scale: 0.9 }} animate={{ opacity: 1, scale: 1 }} exit={{ opacity: 0, scale: 0.9 }}
            className="fixed inset-0 flex items-center justify-center z-40 bg-black/60 backdrop-blur-sm" onClick={() => setResult(null)}>
            <div className="glass rounded-2xl p-8 max-w-sm w-full mx-4" onClick={e => e.stopPropagation()}>
              <div className="text-center">
                <div className="text-6xl mb-4">{result.success ? '🎉' : '😞'}</div>
                <h3 className={`text-xl font-bold mb-2 ${result.success ? 'text-green-400' : 'text-red-400'}`}>{result.success ? '抢券成功！' : '抢券失败'}</h3>
                <p className="text-gray-400 text-sm mb-4">{result.message}</p>
                {result.success && (
                  <div className="space-y-1 text-xs text-gray-500 mb-4">
                    <p>订单号: <span className="text-gray-300 font-mono">{result.orderNo?.slice(0, 16)}...</span></p>
                    <p>用户权重: <span className="text-purple-400">{result.userWeight}</span></p>
                    <p>耗时: <span className="text-blue-400">{result.elapsed}ms</span></p>
                  </div>
                )}
                <button onClick={() => setResult(null)} className="gradient-btn text-white px-6 py-2 rounded-xl text-sm">确定</button>
              </div>
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  )
}