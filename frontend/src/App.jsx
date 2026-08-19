import { BrowserRouter, Routes, Route } from 'react-router-dom'
import { createContext, useContext, useState, useEffect } from 'react'
import Navbar from './components/Navbar'
import Home from './pages/Home'
import Shop from './pages/Shop'
import Orders from './pages/Orders'
import Login from './pages/Login'
import Admin from './pages/Admin'
import AIPage from './pages/AIPage'

const AuthCtx = createContext(null)
export const useAuth = () => useContext(AuthCtx)

function AuthProvider({ children }) {
  const [user, setUser] = useState(() => {
    try { return JSON.parse(localStorage.getItem('user') || 'null') } catch { return null }
  })

  useEffect(() => {
    const sync = () => { setUser(JSON.parse(localStorage.getItem('user')||'null')) }
    window.addEventListener('storage', sync)
    return () => window.removeEventListener('storage', sync)
  }, [])

  const login = (u) => { setUser(u); localStorage.setItem('user', JSON.stringify(u)) }
  const logout = () => { localStorage.clear(); setUser(null); window.dispatchEvent(new Event('storage')) }
  const isLogin = !!user
  const role = user?.role || 'USER'

  return <AuthCtx.Provider value={{ user, isLogin, role, login, logout }}>{children}</AuthCtx.Provider>
}

export default function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <div className="min-h-screen bg-base-300" data-theme="dark">
          <Navbar />
          <main className="max-w-6xl mx-auto px-4 py-6">
            <Routes>
              <Route path="/" element={<Home />} />
              <Route path="/shop/:id" element={<Shop />} />
              <Route path="/orders" element={<Orders />} />
              <Route path="/login" element={<Login />} />
              <Route path="/admin" element={<Admin />} />
              <Route path="/ai" element={<AIPage />} />
            </Routes>
          </main>
        </div>
      </BrowserRouter>
    </AuthProvider>
  )
}