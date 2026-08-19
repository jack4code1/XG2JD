import { createContext, useContext, useState, useEffect } from 'react'

const AuthCtx = createContext(null)
export const useAuth = () => useContext(AuthCtx)

export function AuthProvider({ children }) {
  const [user, setUser] = useState(() => {
    try { return JSON.parse(localStorage.getItem('user') || 'null') } catch { return null }
  })
  useEffect(() => {
    const sync = () => setUser(JSON.parse(localStorage.getItem('user')||'null'))
    window.addEventListener('storage', sync)
    return () => window.removeEventListener('storage', sync)
  }, [])
  const login = (u) => { setUser(u); localStorage.setItem('user', JSON.stringify(u)) }
  const logout = () => { localStorage.clear(); setUser(null); window.dispatchEvent(new Event('storage')) }
  return <AuthCtx.Provider value={{ user, isLogin: !!user, role: user?.role||'USER', login, logout }}>{children}</AuthCtx.Provider>
}