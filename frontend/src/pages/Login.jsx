import { useState } from 'react'
import client from '../api/client'

export default function Login() {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [role, setRole] = useState('USER')
  const [msg, setMsg] = useState('')

  const handleSubmit = async (e, isRegister) => {
    e.preventDefault(); setMsg('')
    try {
      if (isRegister) await client.post('/auth/register', { username, password, role })
      const { data } = await client.post('/auth/login', { username, password })
      if (!data.accessToken) { setMsg('登录失败'); return }
      localStorage.setItem('accessToken', data.accessToken)
      localStorage.setItem('refreshToken', data.refreshToken)
      localStorage.setItem('user', JSON.stringify({ username, role }))
      window.dispatchEvent(new Event('storage'))
      window.location.reload()
    } catch (e) { setMsg(e.response?.data?.message || '用户名或密码错误') }
  }

  return (
    <div className="max-w-sm mx-auto mt-16">
      <div className="card bg-base-100 shadow-xl">
        <div className="card-body">
          <h2 className="card-title justify-center gradient-text text-2xl">秒杀商城</h2>
          <div className="tabs tabs-boxed justify-center my-2">
            <button className={`tab ${role==='USER'?'tab-active':''}`} onClick={()=>setRole('USER')}>👤 用户</button>
            <button className={`tab ${role==='MERCHANT'?'tab-active':''}`} onClick={()=>setRole('MERCHANT')}>🏪 商家</button>
          </div>
          <form onSubmit={(e) => handleSubmit(e, true)}>
            <input value={username} onChange={e=>setUsername(e.target.value)} placeholder="用户名" required className="input input-bordered w-full mb-3" />
            <input value={password} onChange={e=>setPassword(e.target.value)} placeholder="密码" type="password" required className="input input-bordered w-full mb-3" />
            <div className="flex gap-2">
              <button type="submit" className="btn btn-primary flex-1">注册并登录</button>
              <button type="button" onClick={(e) => handleSubmit(e, false)} className="btn btn-outline flex-1">登录</button>
            </div>
          </form>
          {msg && <div className="alert alert-error mt-3 py-2 text-sm">{msg}</div>}
        </div>
      </div>
    </div>
  )
}