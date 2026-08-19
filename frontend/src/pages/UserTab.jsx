import { useState, useEffect, useCallback } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import GlassCard from '../components/GlassCard'
import Confetti from '../components/Confetti'
import client from '../api/client'

const ICONS = { '餐饮': '🍔', '服饰': '👗', '数码': '📱', '美妆': '💄', '其他': '🛒' }
const STATUS = { CREATED: '待支付', PAYING: '支付中', PAID: '已支付', USED: '已使用', CANCELED: '已取消', EXPIRED: '已过期' }

export default function UserTab({ auth }) {
  const { user, loginSuccess, logout } = auth
  const [username, setUsername] = useState(''); const [password, setPassword] = useState('')
  const [role, setRole] = useState('USER')
  const [merchants, setMerchants] = useState([])
  const [selectedMerchant, setSelectedMerchant] = useState(null)
  const [result, setResult] = useState(null); const [showConfetti, setShowConfetti] = useState(false)
  const [loading, setLoading] = useState(false)
  const [tab, setTab] = useState('mall') // mall | me
  const [orders, setOrders] = useState([])
  const [ordersLoading, setOrdersLoading] = useState(false)

  const loadMerchants = useCallback(async () => {
    try { const { data } = await client.get('/merchant/list'); setMerchants(Array.isArray(data) ? data : []) }
    catch { setMerchants([]) }
  }, [])

  const loadOrders = useCallback(async () => {
    setOrdersLoading(true)
    try {
      // 遍历所有商家的券，查每个券的用户订单
      const all = []
      try { const { data } = await client.get('/coupon/active');
        for (const c of (Array.isArray(data)?data:[])) {
          try { const { data: od } = await client.get(`/order/user/${c.id}`); if(Array.isArray(od)) all.push(...od.map(o=>({...o,couponName:c.couponName}))) }
          catch {}
        }
      } catch {}
      setOrders(all)
    } catch { setOrders([]) }
    setOrdersLoading(false)
  }, [])

  useEffect(() => { if (user?.role === 'USER') { loadMerchants() } }, [user, loadMerchants])
  useEffect(() => { if (tab === 'me' && user) loadOrders() }, [tab, user, loadOrders])

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

  const handlePay = async (orderNo) => {
    try { await client.post(`/order/${orderNo}/pay`); alert('支付成功'); loadOrders() }
    catch { alert('支付失败') }
  }
  const handleCancel = async (orderNo) => {
    try { await client.post(`/order/${orderNo}/cancel`); alert('已取消'); loadOrders() }
    catch { alert('取消失败') }
  }

  // ─── Login Page ───
  if (!user) {
    return (
      <GlassCard className="max-w-sm mx-auto mt-10">
        <h2 className="text-xl font-semibold mb-6 text-center gradient-text">秒杀商城 · 登录/注册</h2>
        <div className="flex gap-2 mb-4">
          {['USER', 'MERCHANT'].map(r => (
            <button key={r} onClick={() => setRole(r)} className={`flex-1 py-2 rounded-xl text-sm font-medium transition-all ${role === r ? 'gradient-btn text-white' : 'glass text-gray-400'}`}>
              {r === 'USER' ? '👤 普通用户' : '🏪 商家'}
            </button>
          ))}
        </div>
        <form onSubmit={handleLogin} className="space-y-4">
          <input value={username} onChange={e => setUsername(e.target.value)} placeholder="用户名" required className="w-full bg-white/5 border border-white/10 rounded-xl px-4 py-3 text-white placeholder-gray-500 focus:outline-none focus:border-purple-500" />
          <input value={password} onChange={e => setPassword(e.target.value)} placeholder="密码" type="password" required className="w-full bg-white/5 border border-white/10 rounded-xl px-4 py-3 text-white placeholder-gray-500 focus:outline-none focus:border-purple-500" />
          <div className="flex gap-3">
            <button type="submit" className="flex-1 gradient-btn text-white py-3 rounded-xl font-medium">注册并登录</button>
            <button type="button" onClick={handleLoginOnly} className="flex-1 glass glass-hover text-gray-300 py-3 rounded-xl font-medium">登录</button>
          </div>
        </form>
      </GlassCard>
    )
  }

  // ─── 我的 ───
  if (tab === 'me') {
    return (
      <div className="space-y-6">
        <Confetti active={showConfetti} />
        {/* Profile Card */}
        <GlassCard>
          <div className="flex items-center gap-4">
            <div className="w-14 h-14 rounded-full gradient-btn flex items-center justify-center text-2xl">{user.username[0].toUpperCase()}</div>
            <div>
              <h2 className="text-lg font-bold">{user.username}</h2>
              <p className="text-xs text-gray-500">普通用户 · 秒杀商城会员</p>
            </div>
          </div>
        </GlassCard>

        {/* Orders */}
        <GlassCard>
          <h3 className="text-sm font-semibold text-gray-400 mb-4">📋 我的订单</h3>
          {ordersLoading ? <p className="text-gray-500 text-sm">加载中...</p>
          : orders.length === 0 ? <p className="text-gray-500 text-sm py-4 text-center">暂无订单，快去商城抢券吧！</p>
          : <div className="space-y-2">
            {orders.map((o, i) => (
              <div key={i} className="flex items-center justify-between py-2 border-b border-white/5 last:border-0">
                <div className="flex-1">
                  <p className="text-sm">{o.couponName || '优惠券'}</p>
                  <p className="text-xs text-gray-500 font-mono">{o.orderNo?.slice(0,14)}...</p>
                </div>
                <span className={`text-xs px-2 py-1 rounded mr-2 ${o.status==='CREATED'?'bg-blue-500/10 text-blue-400':o.status==='PAID'?'bg-green-500/10 text-green-400':'bg-gray-500/10 text-gray-500'}`}>
                  {STATUS[o.status] || o.status}
                </span>
                {o.status === 'CREATED' && (
                  <div className="flex gap-1">
                    <button onClick={()=>handlePay(o.orderNo)} className="text-xs gradient-btn text-white px-2 py-1 rounded">支付</button>
                    <button onClick={()=>handleCancel(o.orderNo)} className="text-xs glass px-2 py-1 rounded text-gray-400">取消</button>
                  </div>
                )}
              </div>
            ))}
          </div>}
        </GlassCard>

        {/* Bottom Nav */}
        <div className="fixed bottom-0 left-0 right-0 glass border-t border-white/5 py-2 px-6 flex justify-around z-30">
          <button onClick={() => { setTab('mall'); setSelectedMerchant(null) }}
            className="flex flex-col items-center gap-1 text-gray-500 hover:text-white">
            <span className="text-xl">🏬</span><span className="text-xs">商城</span>
          </button>
          <button onClick={() => setTab('me')}
            className="flex flex-col items-center gap-1 text-purple-400">
            <span className="text-xl">👤</span><span className="text-xs">我的</span>
          </button>
        </div>
        <div className="h-16" />
      </div>
    )
  }

  // ─── Merchant Detail ───
  if (selectedMerchant) {
    const m = selectedMerchant
    return (
      <div className="space-y-4 pb-20">
        <Confetti active={showConfetti} />
        <button onClick={() => setSelectedMerchant(null)} className="text-sm text-gray-400 hover:text-white mb-2">← 返回商城</button>
        <GlassCard>
          <div className="flex items-center gap-4 mb-4">
            <span className="text-4xl">{ICONS[m.category] || '🛒'}</span>
            <div><h2 className="text-xl font-bold">{m.shopName}</h2><p className="text-xs text-gray-500">{m.shopDesc || m.category} · {m.couponCount}张券</p></div>
          </div>
        </GlassCard>
        <div className="grid gap-3">
          {(m.coupons || []).map(c => {
            const remain = c.remainStock || 0, total = c.totalStock || 100
            const active = new Date(c.endTime).getTime() > Date.now()
            return (
              <GlassCard key={c.id} className="glass-hover">
                <div className="flex items-center justify-between">
                  <div><h3 className="font-bold">{c.couponName}</h3><p className="text-xs text-gray-500">剩余 {remain}/{total} · {active?'进行中':'已结束'}</p></div>
                  <button onClick={() => handleSeckill(c.id)} disabled={!active||loading}
                    className={`px-6 py-2 rounded-xl text-sm font-bold ${active?'gradient-btn text-white':'bg-white/5 text-gray-600'}`}>
                    {loading?'...':active?'⚡抢':'结束'}
                  </button>
                </div>
              </GlassCard>
            )
          })}
        </div>
        <AnimatePresence>{result && <ResultModal result={result} onClose={()=>setResult(null)} />}</AnimatePresence>
        <BottomNav tab={tab} setTab={setTab} setSelectedMerchant={setSelectedMerchant} />
      </div>
    )
  }

  // ─── Mall Home ───
  return (
    <div className="space-y-6 pb-20">
      <GlassCard>
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-full gradient-btn flex items-center justify-center text-lg">{user.username[0].toUpperCase()}</div>
          <div>
            <p className="text-sm font-medium">{user.username}</p>
            <p className="text-xs text-gray-500">🔥 秒杀商城 · {merchants.length}家店铺</p>
          </div>
        </div>
      </GlassCard>
      <h2 className="text-lg font-bold">🏬 精选商家</h2>
      <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
        {merchants.map(m => (
          <motion.div key={m.id} whileHover={{ scale: 1.03 }} onClick={() => setSelectedMerchant(m)}
            className="glass glass-hover rounded-2xl p-5 cursor-pointer">
            <div className="text-4xl mb-3 text-center">{ICONS[m.category] || '🛒'}</div>
            <h3 className="font-bold text-sm text-center">{m.shopName}</h3>
            <p className="text-xs text-gray-500 text-center mt-1">{m.couponCount}张优惠券</p>
          </motion.div>
        ))}
        {merchants.length === 0 && <GlassCard className="col-span-full text-center py-8 text-gray-500">暂无商家入驻</GlassCard>}
      </div>
      <BottomNav tab={tab} setTab={setTab} setSelectedMerchant={setSelectedMerchant} />
    </div>
  )
}

function BottomNav({ tab, setTab, setSelectedMerchant }) {
  return (
    <div className="fixed bottom-0 left-0 right-0 glass border-t border-white/5 py-2 px-6 flex justify-around z-30">
      <button onClick={() => { setTab('mall'); setSelectedMerchant(null) }}
        className={`flex flex-col items-center gap-1 ${tab==='mall'?'text-purple-400':'text-gray-500'}`}>
        <span className="text-xl">🏬</span><span className="text-xs">商城</span>
      </button>
      <button onClick={() => setTab('me')}
        className={`flex flex-col items-center gap-1 ${tab==='me'?'text-purple-400':'text-gray-500'}`}>
        <span className="text-xl">👤</span><span className="text-xs">我的</span>
      </button>
    </div>
  )
}

function ResultModal({ result, onClose }) {
  return (
    <motion.div initial={{opacity:0,scale:.9}} animate={{opacity:1,scale:1}} className="fixed inset-0 flex items-center justify-center z-40 bg-black/60 backdrop-blur-sm" onClick={onClose}>
      <div className="glass rounded-2xl p-8 max-w-sm w-full mx-4" onClick={e=>e.stopPropagation()}>
        <div className="text-center">
          <div className="text-5xl mb-3">{result.success?'🎉':'😞'}</div>
          <h3 className={`text-lg font-bold ${result.success?'text-green-400':'text-red-400'}`}>{result.success?'抢券成功！':'抢券失败'}</h3>
          <p className="text-gray-400 text-sm my-2">{result.message}</p>
          {result.success && <div className="text-xs text-gray-500 space-y-1"><p>权重: {result.userWeight}</p><p>耗时: {result.elapsed}ms</p></div>}
          <button onClick={onClose} className="gradient-btn text-white px-6 py-2 rounded-xl text-sm mt-3">确定</button>
        </div>
      </div>
    </motion.div>
  )
}