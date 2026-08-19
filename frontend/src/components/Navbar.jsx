import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../App'

export default function Navbar() {
  const { user, isLogin, role, logout } = useAuth()
  const nav = useNavigate()

  const handleLogout = () => { logout(); nav('/login') }

  return (
    <div className="navbar bg-base-100 shadow-lg">
      <div className="flex-1">
        <Link to="/" className="btn btn-ghost text-xl">
          🔥 <span className="gradient-text font-bold">秒杀商城</span>
        </Link>
      </div>
      <div className="flex-none gap-2">
        {isLogin ? (
          <>
            {role === 'USER' ? (
              <>
                <Link to="/" className="btn btn-ghost btn-sm">🏬 商城</Link>
                <Link to="/orders" className="btn btn-ghost btn-sm">📋 订单</Link>
              </>
            ) : (
              <>
                <Link to="/admin" className="btn btn-ghost btn-sm">🏪 管理</Link>
                <Link to="/ai" className="btn btn-ghost btn-sm">🤖 AI</Link>
              </>
            )}
            <div className="dropdown dropdown-end">
              <div tabIndex={0} className="btn btn-ghost btn-circle avatar">
                <div className="w-8 rounded-full bg-primary text-primary-content flex items-center justify-center text-sm font-bold">
                  {user.username[0].toUpperCase()}
                </div>
              </div>
              <ul tabIndex={0} className="menu menu-sm dropdown-content mt-3 z-[1] p-2 shadow bg-base-100 rounded-box w-52">
                <li className="menu-title">{user.username} ({role==='MERCHANT'?'商家':'用户'})</li>
                <li><a onClick={handleLogout}>🚪 退出登录</a></li>
              </ul>
            </div>
          </>
        ) : (
          <Link to="/login" className="btn btn-primary btn-sm">登录</Link>
        )}
      </div>
    </div>
  )
}