import { useState, useCallback } from 'react'
import client from '../api/client'

export default function useAuth() {
  const [user, setUser] = useState(() => {
    const u = localStorage.getItem('user')
    return u ? JSON.parse(u) : null
  })

  const login = useCallback(async (username, password) => {
    const { data } = await client.post('/auth/login', { username, password })
    localStorage.setItem('accessToken', data.accessToken)
    localStorage.setItem('refreshToken', data.refreshToken)
    const u = { username, expiresIn: data.expiresIn }
    localStorage.setItem('user', JSON.stringify(u))
    setUser(u)
    return data
  }, [])

  const register = useCallback(async (username, password) => {
    const { data } = await client.post('/auth/register', { username, password })
    return data
  }, [])

  const logout = useCallback(() => {
    localStorage.clear()
    setUser(null)
  }, [])

  return { user, login, register, logout }
}