import { useState, useEffect } from 'react'
import client from '../api/client'

export default function Shop({ shopId, onBack }) {
  const [m,setM]=useState(null); const [r,setR]=useState(null)
  const load=()=>client.get('/merchant/list').then(({data})=>setM((data||[]).find(x=>x.id==shopId)||null)).catch(()=>{})
  useEffect(()=>{load()},[shopId])
  const go=async cid=>{try{const{data}=await client.post('/seckill/execute',{couponId:cid,deviceFingerprint:'fp-'+Math.random().toString(36).slice(2,10)});setR(data);if(data.success)load()}catch{setR({success:false,message:'系统繁忙，请稍后再试'})}}
  if(!m) return <div className="empty">正在打开店铺…</div>
  return <><button className="back" onClick={onBack}>← 返回发现页</button><section className="shop-hero"><div className="eyebrow">TODAY'S SHOP</div><h1>{m.shopName}</h1><p>{m.category||'精选好店'}　·　正在营业　·　{m.couponCount||0} 张优惠券</p></section><div className="section-head"><div><h2>限时好券</h2><div className="muted">手慢就要等下一场啦</div></div></div><div className="coupon-list">{(m.coupons||[]).map(c=>{const remain=c.remainStock||0,total=c.totalStock||100,pct=Math.max(0,remain/total*100),active=new Date(c.endTime)>Date.now();return <div className="coupon" key={c.id}><div className="coupon-value">立减</div><div className="coupon-info"><strong>{c.couponName}</strong><small>剩余 {remain} / {total}　·　每人限领 {c.perUserMax||1} 张</small><div className="stock"><i style={{width:(100-pct)+'%'}}/></div></div><button className="primary-btn" onClick={()=>go(c.id)} disabled={!active||remain<1}>{active&&remain>0?'立即抢':'已结束'}</button></div>})}</div>{r&&<div className="modal-mask" onClick={()=>setR(null)}><div className="modal" onClick={e=>e.stopPropagation()}><div className="modal-icon">{r.success?'🎉':'😞'}</div><h3>{r.success?'抢券成功！':'抢券失败'}</h3><p>{r.message}{r.success&&<><br/>本次分配权重：{r.userWeight}</>}</p><button className="primary-btn" onClick={()=>setR(null)}>知道了</button></div></div>}</>
}
