import { useState, useEffect } from 'react'
import { useAuth } from '../AuthContext'
import client from '../api/client'

const ICONS = { '餐饮':'🍔','服饰':'👗','数码':'📱','美妆':'💄','其他':'🛒' }

export default function Home({ onShopClick }) {
  const { isLogin } = useAuth()
  const [list, setList] = useState([])
  useEffect(()=>{ isLogin && client.get('/merchant/list').then(({data})=>setList(Array.isArray(data)?data:[])).catch(()=>{}) },[isLogin])

  return (
    <div>
      <div style={{ background:'linear-gradient(135deg,#6366f1,#8b5cf6)', borderRadius:20, padding:'48px 24px', textAlign:'center', marginBottom:32, color:'white' }}>
        <h1 style={{ fontSize:36, fontWeight:800, margin:0 }}>🔥 限时秒杀</h1>
        <p style={{ fontSize:16, opacity:.85, marginTop:8 }}>精选商家 · 超值优惠券 · 手慢无</p>
      </div>
      <h2 style={{ fontSize:22, fontWeight:700, marginBottom:20, color:'#1f2937' }}>🏬 精选商家</h2>
      <div style={{ display:'grid', gridTemplateColumns:'repeat(auto-fill, minmax(200px, 1fr))', gap:16 }}>
        {list.map(m => (
          <div key={m.id} onClick={()=>onShopClick(m.id)}
            style={{ background:'white', borderRadius:16, padding:24, textAlign:'center', cursor:'pointer', boxShadow:'0 2px 12px rgba(0,0,0,0.06)', transition:'all .2s' }}
            onMouseEnter={e=>e.currentTarget.style.transform='translateY(-2px)'}
            onMouseLeave={e=>e.currentTarget.style.transform='none'}>
            <div style={{ fontSize:48, marginBottom:12 }}>{ICONS[m.category]||'🛒'}</div>
            <div style={{ fontSize:15, fontWeight:700, color:'#1f2937' }}>{m.shopName}</div>
            <div style={{ display:'inline-block', background:'#eef2ff', color:'#6366f1', padding:'2px 10px', borderRadius:20, fontSize:12, margin:'6px 0' }}>{m.category||'其他'}</div>
            <div style={{ fontSize:13, color:'#9ca3af', marginTop:4 }}>{m.couponCount} 张优惠券</div>
          </div>
        ))}
      </div>
    </div>
  )
}