import { useState, useEffect } from 'react'
import client from '../api/client'

const BAR = (pct) => pct>50?'#6366f1':pct>20?'#f59e0b':'#ef4444'

export default function Shop({ shopId, onBack }) {
  const [m, setM] = useState(null)
  const [r, setR] = useState(null)

  useEffect(()=>{ client.get('/merchant/list').then(({data})=>setM((data||[]).find(x=>x.id==shopId)||null)).catch(()=>{}) },[shopId])

  const go = async (cid) => {
    try {
      const { data } = await client.post('/seckill/execute', { couponId:cid, deviceFingerprint:'fp-'+Math.random().toString(36).slice(2,10) })
      setR(data)
      if(data.success) client.get('/merchant/list').then(({data:d})=>setM((d||[]).find(x=>x.id==shopId)||null)).catch(()=>{})
    } catch { setR({success:false,message:'系统繁忙'}) }
  }

  if (!m) return <div style={{textAlign:'center',padding:60}}><div style={{width:40,height:40,border:'3px solid #e5e7eb',borderTopColor:'#6366f1',borderRadius:'50%',animation:'spin 0.8s linear infinite',margin:'0 auto'}} /></div>

  return (
    <div>
      <button onClick={onBack} style={{border:'none',background:'none',color:'#6366f1',fontSize:14,cursor:'pointer',marginBottom:16}}>← 返回商城</button>
      <div style={{background:'white',borderRadius:16,padding:24,boxShadow:'0 2px 12px rgba(0,0,0,0.06)',marginBottom:20}}>
        <h1 style={{fontSize:24,fontWeight:800,color:'#1f2937'}}>{m.shopName}</h1>
      </div>
      <div style={{display:'flex',flexDirection:'column',gap:12}}>
        {(m.coupons||[]).map(c => {
          const remain=c.remainStock||0, total=c.totalStock||100, pct=remain/total*100, active=new Date(c.endTime)>Date.now()
          return (
            <div key={c.id} style={{background:'white',borderRadius:14,padding:20,boxShadow:'0 2px 8px rgba(0,0,0,0.05)',display:'flex',alignItems:'center',justifyContent:'space-between'}}>
              <div style={{flex:1}}>
                <div style={{fontSize:16,fontWeight:700,color:'#1f2937'}}>{c.couponName}</div>
                <div style={{display:'flex',alignItems:'center',gap:12,marginTop:8}}>
                  <div style={{flex:1,height:6,background:'#f3f4f6',borderRadius:3,overflow:'hidden'}}>
                    <div style={{width:`${100-pct}%`,height:'100%',background:BAR(pct),borderRadius:3,transition:'width .5s'}} />
                  </div>
                  <span style={{fontSize:13,color:'#6b7280'}}>剩余 {remain}/{total}</span>
                </div>
              </div>
              <button onClick={()=>go(c.id)} disabled={!active}
                style={{marginLeft:16,padding:'10px 24px',borderRadius:10,border:'none',fontSize:14,fontWeight:700,cursor:active?'pointer':'not-allowed',
                  background:active?'linear-gradient(135deg,#6366f1,#8b5cf6)':'#e5e7eb',color:active?'white':'#9ca3af',whiteSpace:'nowrap'}}>
                {active?'⚡ 抢券':'已结束'}
              </button>
            </div>
          )
        })}
      </div>
      {r && (
        <div style={{position:'fixed',inset:0,background:'rgba(0,0,0,0.4)',display:'flex',alignItems:'center',justifyContent:'center',zIndex:100}} onClick={()=>setR(null)}>
          <div style={{background:'white',borderRadius:20,padding:40,textAlign:'center',maxWidth:360}} onClick={e=>e.stopPropagation()}>
            <div style={{fontSize:64,marginBottom:12}}>{r.success?'🎉':'😞'}</div>
            <div style={{fontSize:20,fontWeight:700,color:r.success?'#10b981':'#ef4444'}}>{r.success?'抢券成功！':'抢券失败'}</div>
            <div style={{fontSize:14,color:'#6b7280',marginTop:8}}>{r.message}</div>
            {r.success && <div style={{fontSize:13,color:'#6366f1',marginTop:4}}>权重: {r.userWeight}</div>}
            <button onClick={()=>setR(null)} style={{marginTop:20,padding:'10px 32px',borderRadius:10,border:'none',background:'linear-gradient(135deg,#6366f1,#8b5cf6)',color:'white',fontSize:14,fontWeight:600,cursor:'pointer'}}>确定</button>
          </div>
        </div>
      )}
    </div>
  )
}