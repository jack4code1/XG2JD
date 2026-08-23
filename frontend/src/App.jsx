import { useEffect, useState } from 'react'
import client from './api/client'
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
  const [notifications, setNotifications] = useState([])
  const [showNotifications, setShowNotifications] = useState(false)
  const landingPage = role === 'MERCHANT' ? 'admin' : 'home'
  const allowedPages = role === 'MERCHANT' ? ['admin', 'ai'] : ['home', 'shop', 'orders']
  const activePage = allowedPages.includes(page) ? page : landingPage

  const goShop = (id) => { setShopId(id); setPage('shop') }
  const goLanding = () => {
    setPage(landingPage)
    setShopId(null)
  }
  const loadNotifications = async () => {
    if (!isLogin) return
    try { const { data } = await client.get('/notifications'); setNotifications(Array.isArray(data) ? data : []) } catch { /* optional UI */ }
  }
  useEffect(() => { loadNotifications() }, [isLogin])
  const markRead = async id => {
    try { await client.post('/notifications/' + id + '/read'); setNotifications(items => items.map(item => item.id === id ? { ...item, readAt: new Date().toISOString() } : item)) } catch { /* keep unread */ }
  }
  const markAllRead = async () => {
    try { await client.post('/notifications/read-all'); setNotifications(items => items.map(item => item.readAt ? item : { ...item, readAt: new Date().toISOString() })) } catch { /* keep unread */ }
  }
  const unread = notifications.filter(item => !item.readAt).length

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
              <button className="logout" onClick={() => { setShowNotifications(true); loadNotifications() }}>通知{unread ? ` (${unread})` : ''}</button>
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
      {showNotifications && <div className="modal-mask" onClick={() => setShowNotifications(false)}><div className="manage-modal" onClick={event => event.stopPropagation()}><div className="detail-heading"><div><span className="eyebrow">NOTIFICATIONS</span><h3>通知中心</h3></div><div><button className="logout" disabled={!unread} onClick={markAllRead}>全部已读</button><button className="detail-close" onClick={() => setShowNotifications(false)}>×</button></div></div>{notifications.length ? notifications.map(item => <button className="manage-item" key={item.id} onClick={() => markRead(item.id)}><div><strong>{item.title}{!item.readAt && ' · 未读'}</strong><span>{item.content}</span></div><div className="manage-item-side"><span>{item.createdAt ? new Date(item.createdAt).toLocaleString('zh-CN') : ''}</span></div></button>) : <div className="empty">暂无通知</div>}</div></div>}
    </div>
  )
}

function NavBtn({ active, onClick, children }) {
  return <button className={`nav-link ${active ? 'active' : ''}`} onClick={onClick}>{children}</button>
}

export default function App() {
  return <AuthProvider><AppShell /></AuthProvider>
}
