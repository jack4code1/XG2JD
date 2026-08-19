import { useState, useEffect } from 'react'
import UserTab from './pages/UserTab'
import MerchantTab from './pages/MerchantTab'
import AiTab from './pages/AiTab'

function useSharedAuth() {
  const [user, setUser] = useState(() => { try { return JSON.parse(localStorage.getItem('user') || 'null') } catch { return null } })
  const [token, setToken] = useState(() => localStorage.getItem('accessToken') || '')
  useEffect(() => {
    const h = () => { setUser(JSON.parse(localStorage.getItem('user')||'null')); setToken(localStorage.getItem('accessToken')||'') }
    window.addEventListener('auth-changed', h); return () => window.removeEventListener('auth-changed', h)
  }, [])
  const logout = () => { localStorage.clear(); setUser(null); setToken(''); window.dispatchEvent(new Event('auth-changed')) }
  const loginSuccess = () => { setUser(JSON.parse(localStorage.getItem('user')||'null')); setToken(localStorage.getItem('accessToken')||''); window.dispatchEvent(new Event('auth-changed')) }
  return { user, token, logout, loginSuccess }
}

export default function App() {
  const [tab, setTab] = useState('user')
  const auth = useSharedAuth()
  const role = auth.user?.role || 'USER'

  return (
    <div className="max-w-5xl mx-auto px-4 py-6">
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-bold gradient-text">
          🔥 {role === 'MERCHANT' ? '商家后台' : '秒杀商城'}
        </h1>
        <div className="flex items-center gap-3">
          {auth.user && (
            <span className="text-xs text-gray-500">
              {auth.user.username} {role === 'MERCHANT' ? '🏪' : '👤'}
            </span>
          )}
          <div className="flex gap-1 glass rounded-xl p-1">
            {role === 'MERCHANT' ? (
              <>
                <TabBtn active={tab==='merchant'} onClick={()=>setTab('merchant')}>🏪 商家</TabBtn>
                <TabBtn active={tab==='ai'} onClick={()=>setTab('ai')}>🤖 AI</TabBtn>
              </>
            ) : (
              <TabBtn active={tab==='user'} onClick={()=>setTab('user')}>👤 商城</TabBtn>
            )}
          </div>
        </div>
      </div>
      <div className="min-h-[80vh]">
        {role === 'MERCHANT' ? (
          tab === 'ai' ? <AiTab auth={auth} /> : <MerchantTab auth={auth} />
        ) : (
          <UserTab auth={auth} />
        )}
      </div>
      <div className="text-center text-xs text-gray-600 mt-8 pb-4">
        Spring AI + DeepSeek · React + Tailwind · QPS 359 P50 98ms
      </div>
    </div>
  )
}

function TabBtn({ active, onClick, children }) {
  return <button onClick={onClick} className={`px-4 py-2 rounded-lg text-sm font-medium transition-all ${active ? 'gradient-btn text-white shadow-lg' : 'text-gray-400 hover:text-white'}`}>{children}</button>
}