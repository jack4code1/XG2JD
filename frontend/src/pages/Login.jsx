import { useState } from 'react'
import client from '../api/client'

export default function Login() {
  const [mode, setMode] = useState('login')
  const [u, su] = useState('')
  const [p, sp] = useState('')
  const [r, sr] = useState('USER')
  const [m, sm] = useState('')

  const submit = async (e) => {
    e.preventDefault()
    sm('')
    try {
      if (mode === 'register') {
        const { data } = await client.post('/auth/register', { username: u, password: p, role: r })
        if (!data.success) {
          sm(data.message)
          return
        }
        setMode('login')
        sp('')
        sm('注册成功，请使用新账号登录')
        return
      }

      const { data } = await client.post('/auth/login', { username: u, password: p })
      if (!data.accessToken) {
        sm('登录失败')
        return
      }
      localStorage.setItem('accessToken', data.accessToken)
      localStorage.setItem('refreshToken', data.refreshToken)
      localStorage.setItem('user', JSON.stringify({ username: u, role: data.role || r }))
      window.dispatchEvent(new Event('storage'))
      window.location.reload()
    } catch (e) {
      sm(e.response?.data?.message || '用户名或密码错误')
    }
  }

  const switchMode = (next) => {
    setMode(next)
    sm('')
  }

  return <div className="login-page">
    <div className="login-frame">
      <section className="login-showcase">
        <div className="login-showcase-mark">火</div>
        <div className="eyebrow">TODAY COUPON</div>
        <h1>限时好券，<br />现在就抢。</h1>
        <p>精选店铺优惠 · 实时库存 · 先到先得</p>
        <div className="login-showcase-foot">今日抢券 · 让每次消费都更划算</div>
      </section>

      <section className="login-card">
        <div className="login-switch">
          <button type="button" className={mode === 'login' ? 'active' : ''} onClick={() => switchMode('login')}>登录</button>
          <button type="button" className={mode === 'register' ? 'active' : ''} onClick={() => switchMode('register')}>注册</button>
        </div>
        <h2>{mode === 'login' ? '欢迎回来' : '创建账号'}</h2>
        <p className="lead">{mode === 'login' ? '登录今日抢券，继续发现限时心动。' : '注册后即可领取优惠券并参与秒杀。'}</p>

        <div className="role-tabs" aria-label="账号类型">
          <button type="button" className={'role-tab ' + (r === 'USER' ? 'active' : '')} onClick={() => sr('USER')}>我是用户</button>
          <button type="button" className={'role-tab ' + (r === 'MERCHANT' ? 'active' : '')} onClick={() => sr('MERCHANT')}>我是商家</button>
        </div>

        <form onSubmit={submit}>
          <div className="field"><label>用户名</label><input className="input" required value={u} onChange={e => su(e.target.value)} placeholder="请输入用户名" autoComplete="username" /></div>
          <div className="field"><label>密码</label><input className="input" required minLength="6" value={p} onChange={e => sp(e.target.value)} placeholder="请输入密码（至少 6 位）" type="password" autoComplete={mode === 'login' ? 'current-password' : 'new-password'} /></div>
          {mode === 'login' && <div className="login-helper"><span>安全登录</span><span>密码加密保护</span></div>}
          <button className="primary-btn login-submit" type="submit">{mode === 'login' ? '登录' : '注册账号'}</button>
        </form>
        {m && <p className={'login-message ' + (m.includes('成功') ? 'success' : '')}>{m}</p>}
        <div className="login-policy">登录即表示同意《用户服务协议》和《隐私政策》</div>
      </section>
    </div>
  </div>
}
