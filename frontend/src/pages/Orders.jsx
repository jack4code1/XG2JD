import { useState, useEffect } from 'react'
import { useAuth } from '../AuthContext'
import client from '../api/client'

const STATUS={CREATED:'待支付',PAYING:'支付中',PAID:'已支付',USED:'已使用',CANCELED:'已取消',EXPIRED:'已过期'}

export default function Orders(){
  const{isLogin}=useAuth(); const[orders,setOrders]=useState([]); const[loading,setLoading]=useState(true)
  useEffect(()=>{if(!isLogin)return;let cancelled=false;const load=async(attempt=0)=>{try{const{data}=await client.get('/order/user');if(cancelled)return;setOrders(Array.isArray(data)?data:[]);setLoading(false);if(!data?.length&&attempt<4)setTimeout(()=>load(attempt+1),500)}catch{if(!cancelled&&attempt<4)setTimeout(()=>load(attempt+1),500);else if(!cancelled)setLoading(false)}};load();return()=>{cancelled=true}},[isLogin])
  const refresh=()=>{setLoading(true);client.get('/order/user').then(({data})=>setOrders(Array.isArray(data)?data:[])).finally(()=>setLoading(false))}
  const pay=async orderNo=>{await client.post('/order/'+orderNo+'/pay');setOrders(orders.map(x=>x.orderNo===orderNo?{...x,status:'PAYING'}:x))}
  const cancel=async orderNo=>{await client.post('/order/'+orderNo+'/cancel');setOrders(orders.map(x=>x.orderNo===orderNo?{...x,status:'CANCELED'}:x))}
  return <><div className="section-head"><div><h2>我的订单</h2><div className="muted">每一次心动，都值得被记录</div></div><button className="logout" onClick={refresh}>刷新订单</button></div>{loading?<div className="empty">正在同步订单状态…</div>:orders.length===0?<div className="empty">还没有订单，去发现页抢一张喜欢的券吧</div>:<div className="panel"><div style={{display:'grid',gap:0}}>{orders.map(o=><div key={o.orderNo} style={{display:'grid',gridTemplateColumns:'1.4fr 1fr 1fr auto',gap:12,alignItems:'center',padding:'16px 0',borderBottom:'1px solid #f3f3f3'}}><div><strong>{o.couponName||'优惠券 #'+o.couponId}</strong><div className="muted">{o.shopName||'-'}</div></div><span className="muted">{o.orderNo?.slice(0,14)}...</span><span className="muted">{STATUS[o.status]||o.status}</span>{o.status==='CREATED'?<div style={{display:'flex',gap:8}}><button className="primary-btn" onClick={()=>pay(o.orderNo)}>支付</button><button className="logout" onClick={()=>cancel(o.orderNo)}>取消</button></div>:<span/>}</div>)}</div></div>}</>
}
