import { useState } from 'react'
import { useAuth } from '../AuthContext'
import client from '../api/client'
const S={card:{background:'white',borderRadius:16,padding:24,boxShadow:'0 2px 12px rgba(0,0,0,0.06)'},inp:{width:'100%',padding:'10px 14px',border:'1.5px solid #e5e7eb',borderRadius:10,fontSize:14,outline:'none',boxSizing:'border-box'},btn:{width:'100%',padding:'12px',borderRadius:10,border:'none',background:'linear-gradient(135deg,#6366f1,#8b5cf6)',color:'white',fontSize:14,fontWeight:600,cursor:'pointer'}}

export default function Admin() {
  const { isLogin } = useAuth()
  const [f,sf]=useState({couponName:'',totalStock:100,perUserMax:1,endHours:24}); const [m,sm]=useState('')
  if(!isLogin) return <div style={{textAlign:'center',padding:60,color:'#9ca3af'}}>请先登录商家账号</div>
  const create=async e=>{e.preventDefault();sm('')
    try{const n=new Date(),d=new Date(n.getTime()+f.endHours*36e5);const{data}=await client.post('/coupon/create',{couponName:f.couponName,totalStock:+f.totalStock,remainStock:+f.totalStock,startTime:n.toISOString(),endTime:d.toISOString(),perUserMax:+f.perUserMax,status:1})
      sm(`✅ ${data.couponName} (ID:${data.id}) 库存${data.totalStock}`)}catch(e){sm('❌ '+(e.response?.data?.message||e.message))}}
  return (
    <div>
      <h2 style={{fontSize:22,fontWeight:700,color:'#1f2937',marginBottom:20}}>🏪 商家管理</h2>
      <div style={{display:'grid',gridTemplateColumns:'1fr 1fr',gap:20}}>
        <div style={S.card}>
          <h3 style={{fontSize:18,fontWeight:700,marginBottom:16}}>📦 创建优惠券</h3>
          <form onSubmit={create}>
            <input value={f.couponName} onChange={e=>sf({...f,couponName:e.target.value})} placeholder="优惠券名称" style={{...S.inp,marginBottom:12}} />
            <div style={{display:'grid',gridTemplateColumns:'1fr 1fr 1fr',gap:10,marginBottom:12}}>
              <div><label style={{fontSize:12,color:'#6b7280'}}>总库存</label><input type="number" value={f.totalStock} onChange={e=>sf({...f,totalStock:e.target.value})} style={S.inp} /></div>
              <div><label style={{fontSize:12,color:'#6b7280'}}>每人限购</label><input type="number" value={f.perUserMax} onChange={e=>sf({...f,perUserMax:e.target.value})} style={S.inp} /></div>
              <div><label style={{fontSize:12,color:'#6b7280'}}>时长(h)</label><input type="number" value={f.endHours} onChange={e=>sf({...f,endHours:e.target.value})} style={S.inp} /></div>
            </div>
            <button type="submit" style={S.btn}>创建活动 + 预热Redis</button>
          </form>
          {m&&<div style={{marginTop:12,padding:'10px 14px',borderRadius:10,fontSize:13,background:m.startsWith('✅')?'#f0fdf4':'#fef2f2',color:m.startsWith('✅')?'#10b981':'#ef4444'}}>{m}</div>}
        </div>
        <div style={S.card}>
          <h3 style={{fontSize:18,fontWeight:700,marginBottom:16}}>⚡ 故障演练</h3>
          <div style={{display:'grid',gridTemplateColumns:'1fr 1fr',gap:10}}>
            {['redis','mq','db','network'].map(s=><button key={s} onClick={async()=>{await client.post(`/drill/start?scenario=${s}`);await new Promise(r=>setTimeout(r,3000));const{data}=await client.post('/drill/recover');alert(`${s} 恢复: ${data.durationMs}ms`)}} style={{padding:'14px',borderRadius:10,border:'1px solid #e5e7eb',background:'white',cursor:'pointer',fontSize:14,fontWeight:600}}>{s==='redis'?'🔴 Redis':s==='mq'?'🟡 MQ':s==='db'?'🟠 DB':'🔵 网络'}</button>)}
          </div>
        </div>
      </div>
    </div>
  )
}