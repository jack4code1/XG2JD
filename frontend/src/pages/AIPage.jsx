import { useState } from 'react'
import client from '../api/client'
const EX=['帮我策划双11秒杀','分析系统数据','生成新用户优惠券文案']

export default function AIPage() {
  const [q,sq]=useState(''); const [p,sp]=useState(''); const [l,sl]=useState(false); const [e,se]=useState(false); const [m,sm]=useState('')
  const sub=async()=>{if(!q.trim())return;sl(true);sp('');sm('')
    try{const{data}=await client.post('/ai/campaign/plan',{query:q});sp(data.plan)}catch{sp('AI服务不可用')}sl(false)}
  const exec=async()=>{se(true);sm('')
    try{const{data}=await client.post('/ai/campaign/execute',{query:q});sm(data.success?`✅ 已创建: ${data.couponName} (ID:${data.couponId})`:`❌ ${data.message}`)}catch{sm('❌ 执行失败')}se(false)}

  return (
    <div>
      <h2 style={{fontSize:22,fontWeight:700,color:'#1f2937',marginBottom:20}}>🤖 AI 运营团队</h2>
      <div style={{background:'white',borderRadius:16,padding:24,boxShadow:'0 2px 12px rgba(0,0,0,0.06)',marginBottom:20}}>
        <div style={{display:'flex',gap:12}}>
          <input value={q} onChange={e=>sq(e.target.value)} onKeyDown={e=>e.key==='Enter'&&sub()} placeholder="描述需求..." style={{flex:1,padding:'12px 16px',border:'1.5px solid #e5e7eb',borderRadius:12,fontSize:14,outline:'none'}} />
          <button onClick={sub} disabled={l} style={{padding:'12px 24px',borderRadius:12,border:'none',background:'linear-gradient(135deg,#6366f1,#8b5cf6)',color:'white',fontSize:14,fontWeight:600,cursor:'pointer'}}>🚀 策划</button>
        </div>
        <div style={{display:'flex',gap:8,marginTop:10}}>{EX.map((x,i)=><button key={i} onClick={()=>sq(x)} style={{padding:'4px 12px',borderRadius:20,border:'1px solid #e5e7eb',background:'white',fontSize:12,cursor:'pointer',color:'#6b7280'}}>{x}</button>)}</div>
      </div>
      {p&&<div style={{background:'white',borderRadius:16,padding:24,boxShadow:'0 2px 12px rgba(0,0,0,0.06)'}}>
        <div style={{display:'flex',justifyContent:'space-between',marginBottom:16}}>
          <span style={{fontSize:13,color:'#9ca3af'}}>4Agent并行分析</span>
          <div style={{display:'flex',gap:8}}>
            <button onClick={()=>navigator.clipboard.writeText(p)} style={{padding:'4px 12px',borderRadius:8,border:'1px solid #e5e7eb',background:'white',fontSize:12,cursor:'pointer'}}>📋</button>
            <button onClick={exec} disabled={e} style={{padding:'4px 14px',borderRadius:8,border:'none',background:'linear-gradient(135deg,#6366f1,#8b5cf6)',color:'white',fontSize:12,cursor:'pointer'}}>🚀 一键创建</button>
          </div>
        </div>
        <pre style={{whiteSpace:'pre-wrap',fontSize:13,lineHeight:1.6,color:'#374151'}}>{p}</pre>
        {m&&<div style={{marginTop:12,padding:'10px 14px',borderRadius:10,fontSize:13,background:m.startsWith('✅')?'#f0fdf4':'#fef2f2',color:m.startsWith('✅')?'#10b981':'#ef4444'}}>{m}</div>}
      </div>}
    </div>
  )
}