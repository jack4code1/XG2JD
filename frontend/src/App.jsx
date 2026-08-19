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
    <div className="min-h-screen" data-theme="dark" style={{background:'#1d232a'}}>
      {/* Navbar */}
      <div className="navbar bg-base-100 shadow-lg">
        <div className="flex-1">
          <button onClick={() => { setPage('home'); setShopId(null) }}
            className="btn btn-ghost text-xl">
            🔥 <span className="gradient-text font-bold">秒杀商城</span>
          </button>
        </div>
        <div className="flex-none gap-2">
          {isLogin ? (
            <>
              {role === 'USER' ? (
                <>
                  <button onClick={() => { setPage('home'); setShopId(null) }}
                    className={`btn btn-ghost btn-sm ${page==='home'?'btn-active':''}`}>🏬 商城</button>
                  <button onClick={() => setPage('orders')}
                    className={`btn btn-ghost btn-sm ${page==='orders'?'btn-active':''}`}>📋 订单</button>
                </>
              ) : (
                <>
                  <button onClick={() => setPage('admin')}
                    className={`btn btn-ghost btn-sm ${page==='admin'?'btn-active':''}`}>🏪 管理</button>
                  <button onClick={() => setPage('ai')}
                    className={`btn btn-ghost btn-sm ${page==='ai'?'btn-active':''}`}>🤖 AI</button>
                </>
              )}
              <div className="dropdown dropdown-end">
                <div tabIndex={0} className="btn btn-ghost btn-circle avatar">
                  <div className="w-8 rounded-full bg-primary text-primary-content flex items-center justify-center text-sm font-bold">
                    {user.username[0].toUpperCase()}
                  </div>
                </div>
                <ul tabIndex={0} className="menu menu-sm dropdown-content mt-3 z-50 p-2 shadow bg-base-100 rounded-box w-52">
                  <li className="menu-title">{user.username}</li>
                  <li><a onClick={() => { logout(); setPage('home') }}>🚪 退出</a></li>
                </ul>
              </div>
            </>
          ) : (
            <button onClick={() => setPage('login')} className="btn btn-primary btn-sm">登录</button>
          )}
        </div>
      </div>

      {/* Content */}
      <main className="max-w-6xl mx-auto px-4 py-6">
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

export default function App() {
  return <AuthProvider><AppShell /></AuthProvider>
}