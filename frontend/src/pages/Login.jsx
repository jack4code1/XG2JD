import { useState } from 'react'
import client from '../api/client'

const S = {
  card: { background: 'white', borderRadius: 16, boxShadow: '0 4px 24px rgba(0,0,0,0.08)', padding: 32, maxWidth: 400, margin: '60px auto' },
  title: { fontSize: 26, fontWeight: 800, textAlign: 'center', background: 'linear-gradient(135deg,#6366f1,#8b5cf6)', WebkitBackgroundClip:'text', WebkitTextFillColor:'transparent', marginBottom: 24 },
  tab: (active) => ({ flex:1, padding:'10px', borderRadius:10, border:'none', cursor:'pointer', fontSize:14, fontWeight:600, background: active?'linear-gradient(135deg,#6366f1,#8b5cf6)':'#f3f4f6', color:active?'white':'#6b7280' }),
  input: { width:'100%', padding:'12px 16px', border:'1.5px solid #e5e7eb', borderRadius:10, fontSize:14, outline:'none', marginBottom:12, boxSizing:'border-box' },
  btn: (primary) => ({ flex:1, padding:'12px', borderRadius:10, border:'none', fontSize:14, fontWeight:600, cursor:'pointer', background:primary?'linear-gradient(135deg,#6366f1,#8b5cf6)':'#f3f4f6', color:primary?'white':'#374151' }),
  error: { background:'#fef2f2', color:'#dc2626', padding:'10px 14px', borderRadius:10, fontSize:13, marginTop:12 }
}

export default function Login() {
  const [u,su]=useState(''); const [p,sp]=useState(''); const [r,sr]=useState('USER'); const [m,sm]=useState('')
  const h = async (e, isReg) => {
    e.preventDefault(); sm('')
    try {
      if (isReg) await client.post('/auth/register', { username: u, password: p, role: r })
      const { data } = await client.post('/auth/login', { username: u, password: p })
      if (!data.accessToken) { sm('登录失败'); return }
      localStorage.setItem('accessToken', data.accessToken)
      localStorage.setItem('refreshToken', data.refreshToken)
      localStorage.setItem('user', JSON.stringify({ username: u, role: r }))
      window.dispatchEvent(new Event('storage'))
      window.location.reload()
    } catch (e) { sm(e.response?.data?.message || '用户名或密码错误') }
  }
  return (
    <div style={S.card}>
      <h2 style={S.title}>秒杀商城</h2>
      <div style={{ display:'flex', gap:8, marginBottom:20 }}>
        <button style={S.tab(r==='USER')} onClick={()=>sr('USER')}>👤 用户</button>
        <button style={S.tab(r==='MERCHANT')} onClick={()=>sr('MERCHANT')}>🏪 商家</button>
      </div>
      <form onSubmit={e=>h(e,true)}>
        <input value={u} onChange={e=>su(e.target.value)} placeholder="用户名" style={S.input} />
        <input value={p} onChange={e=>sp(e.target.value)} placeholder="密码" type="password" style={S.input} />
        <div style={{ display:'flex', gap:10 }}>
          <button type="submit" style={S.btn(true)}>注册并登录</button>
          <button type="button" onClick={e=>h(e,false)} style={S.btn(false)}>登录</button>
        </div>
      </form>
      {m && <div style={S.error}>{m}</div>}
    </div>
  )
}