import { useState } from 'react'
import { AuthProvider, useAuth } from './AuthContext'
import Login from './pages/Login'
import Home from './pages/Home'
import Shop from './pages/Shop'
import Orders from './pages/Orders'
import Admin from './pages/Admin'
import AIPage from './pages/AIPage'

function AppShell() {
  const { user, isLogin, role, logout } = useAuth()
  const [page, setPage] = useState('home')
  const [shopId, setShopId] = useState(null)

  const goShop = (id) => { setShopId(id); setPage('shop') }

  return (
    <div style={{ minHeight: '100vh', background: '#f3f4f6' }}>
      {/* Navbar */}
      <div style={{ background: 'white', boxShadow: '0 1px 3px rgba(0,0,0,0.1)', padding: '0 24px', display: 'flex', alignItems: 'center', justifyContent: 'space-between', height: 64 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
          <button onClick={() => { setPage('home'); setShopId(null) }}
            style={{ fontSize: 22, fontWeight: 800, border: 'none', background: 'none', cursor: 'pointer', color: '#6366f1' }}>
            🔥 秒杀商城
          </button>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          {isLogin ? (
            <>
              {role === 'USER' ? (
                <>
                  <NavBtn active={page==='home'} onClick={()=>{setPage('home');setShopId(null)}}>🏬 商城</NavBtn>
                  <NavBtn active={page==='orders'} onClick={()=>setPage('orders')}>📋 订单</NavBtn>
                </>
              ) : (
                <>
                  <NavBtn active={page==='admin'} onClick={()=>setPage('admin')}>🏪 管理</NavBtn>
                  <NavBtn active={page==='ai'} onClick={()=>setPage('ai')}>🤖 AI</NavBtn>
                </>
              )}
              <div style={{ width: 36, height: 36, borderRadius: '50%', background: 'linear-gradient(135deg,#6366f1,#8b5cf6)', color:'white', display:'flex',alignItems:'center',justifyContent:'center', fontWeight:700, fontSize:14 }}>
                {user.username[0].toUpperCase()}
              </div>
              <span style={{ fontSize: 13, color: '#6b7280' }}>{user.username}</span>
              <button onClick={() => { logout(); setPage('login') }}
                style={{ border: '1px solid #e5e7eb', background: 'white', padding: '6px 14px', borderRadius: 8, fontSize: 13, cursor: 'pointer', color: '#ef4444' }}>
                退出
              </button>
            </>
          ) : (
            <button onClick={() => setPage('login')}
              style={{ background: 'linear-gradient(135deg,#6366f1,#8b5cf6)', color:'white', border:'none', padding:'8px 20px', borderRadius:10, fontSize:14, fontWeight:600, cursor:'pointer' }}>
              登录
            </button>
          )}
        </div>
      </div>

      {/* Content */}
      <main style={{ maxWidth: 1200, margin: '0 auto', padding: '32px 16px' }}>
        {!isLogin && page !== 'login' ? <Login /> :
         page === 'login' ? <Login /> :
         page === 'shop' ? <Shop shopId={shopId} onBack={() => { setPage('home'); setShopId(null) }} /> :
         page === 'orders' ? <Orders /> :
         page === 'admin' ? <Admin /> :
         page === 'ai' ? <AIPage /> :
         <Home onShopClick={goShop} />}
      </main>
    </div>
  )
}

function NavBtn({ active, onClick, children }) {
  return (
    <button onClick={onClick} style={{
      border: 'none', background: active ? '#eef2ff' : 'transparent',
      color: active ? '#6366f1' : '#6b7280', padding: '8px 16px',
      borderRadius: 8, fontSize: 14, fontWeight: active ? 600 : 400, cursor: 'pointer'
    }}>{children}</button>
  )
}

export default function App() {
  return <AuthProvider><AppShell /></AuthProvider>
}