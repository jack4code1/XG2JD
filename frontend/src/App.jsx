import { useState } from 'react'
import { AuthProvider, useAuth } from './AuthContext'
import Login from './pages/Login'
import Home from './pages/Home'
import Shop from './pages/Shop'
import Orders from './pages/Orders'
import Admin from './pages/Admin'
import AIPage from './pages/AIPage'
import './App.css'

function AppShell() {
  const { user, isLogin, role, logout } = useAuth()
  const [page, setPage] = useState(() => role === 'MERCHANT' ? 'admin' : 'home')
  const [shopId, setShopId] = useState(null)
  const landingPage = role === 'MERCHANT' ? 'admin' : 'home'
  const allowedPages = role === 'MERCHANT' ? ['admin', 'ai'] : ['home', 'shop', 'orders']
  const activePage = allowedPages.includes(page) ? page : landingPage

  const goShop = (id) => { setShopId(id); setPage('shop') }
  const goLanding = () => {
    setPage(landingPage)
    setShopId(null)
  }

  return (
    <div className="app">
      <header className="topbar">
        <button className="brand" onClick={goLanding}><span className="brand-mark">火</span>{role === 'MERCHANT' && isLogin ? '商家运营台' : '今日抢券'}</button>
        <div className="nav-right">
          {isLogin ? (
            <>
              {role === 'USER' ? (
                <>
                  <NavBtn active={activePage==='home'} onClick={()=>{setPage('home');setShopId(null)}}>🏬 商城</NavBtn>
                  <NavBtn active={activePage==='orders'} onClick={()=>setPage('orders')}>📋 订单</NavBtn>
                </>
              ) : (
                <>
                  <NavBtn active={activePage==='admin'} onClick={()=>setPage('admin')}>🏪 管理</NavBtn>
                  <NavBtn active={activePage==='ai'} onClick={()=>setPage('ai')}>🤖 AI</NavBtn>
                </>
              )}
              <div className="user-chip"><div className="avatar">{user.username[0].toUpperCase()}</div><span>{user.username}</span></div>
              <button className="logout" onClick={() => { logout(); setPage('login') }}>退出</button>
            </>
          ) : (
            <button className="primary-btn" onClick={() => setPage('login')}>登录 / 注册</button>
          )}
        </div>
      </header>
      <main className="shell">
        {!isLogin || page === 'login' ? <Login /> :
         role === 'MERCHANT' ? (
           activePage === 'ai' ? <AIPage /> : <Admin />
         ) : (
           activePage === 'shop' ? <Shop shopId={shopId} onBack={goLanding} onOrders={() => setPage('orders')} /> :
           activePage === 'orders' ? <Orders /> :
           <Home onShopClick={goShop} />
         )}
      </main>
    </div>
  )
}

function NavBtn({ active, onClick, children }) {
  return <button className={`nav-link ${active ? 'active' : ''}`} onClick={onClick}>{children}</button>
}

export default function App() {
  return <AuthProvider><AppShell /></AuthProvider>
}
