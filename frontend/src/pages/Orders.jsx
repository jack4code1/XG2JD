import { useState, useEffect } from 'react'
import { useAuth } from '../AuthContext'
import client from '../api/client'

const STATUS = { CREATED:'待支付',PAYING:'支付中',PAID:'已支付',USED:'已使用',CANCELED:'已取消',EXPIRED:'已过期' }
const STYLE = { CREATED: {bg:'#eff6ff',c:'#3b82f6'}, PAID: {bg:'#f0fdf4',c:'#10b981'} }

export default function Orders() {
  const { isLogin } = useAuth()
  const [orders, setOrders] = useState([])
  useEffect(()=>{
    if(!isLogin) return
    client.get('/merchant/list').then(async ({data:m})=>{
      const all=[]
      for(const x of(m||[])) for(const c of(x.coupons||[])){
        try { const {data}=await client.get(`/order/user/${c.id}`); if(Array.isArray(data)) all.push(...data.map(o=>({...o,couponName:c.couponName,shopName:x.shopName}))) } catch {}
      }
      setOrders(all)
    }).catch(()=>{})
  },[isLogin])

  return (
    <div>
      <h2 style={{fontSize:22,fontWeight:700,color:'#1f2937',marginBottom:20}}>📋 我的订单</h2>
      {orders.length===0 ? <div style={{textAlign:'center',padding:60,color:'#9ca3af'}}>暂无订单</div> :
      <div style={{background:'white',borderRadius:16,boxShadow:'0 2px 12px rgba(0,0,0,0.06)',overflow:'hidden'}}>
        <table style={{width:'100%',borderCollapse:'collapse'}}>
          <thead><tr style={{background:'#f9fafb'}}>
            {['订单号','商家','优惠券','状态','操作'].map(h=><th key={h} style={{padding:'12px 16px',textAlign:'left',fontSize:13,fontWeight:600,color:'#6b7280'}}>{h}</th>)}
          </tr></thead>
          <tbody>
            {orders.map(o=>(
              <tr key={o.orderNo} style={{borderTop:'1px solid #f3f4f6'}}>
                <td style={{padding:'12px 16px',fontSize:13,fontFamily:'monospace'}}>{o.orderNo?.slice(0,14)}...</td>
                <td style={{padding:'12px 16px',fontSize:13}}>{o.shopName||'-'}</td>
                <td style={{padding:'12px 16px',fontSize:13}}>{o.couponName||'-'}</td>
                <td style={{padding:'12px 16px'}}><span style={{padding:'2px 10px',borderRadius:20,fontSize:12,background:(STYLE[o.status]||{}).bg||'#f3f4f6',color:(STYLE[o.status]||{}).c||'#6b7280'}}>{STATUS[o.status]||o.status}</span></td>
                <td style={{padding:'12px 16px'}}>
                  {o.status==='CREATED'&&<div style={{display:'flex',gap:6}}>
                    <button onClick={async()=>{await client.post(`/order/${o.orderNo}/pay`);setOrders(orders.map(x=>x.orderNo===o.orderNo?{...x,status:'PAID'}:x))}}
                      style={{padding:'4px 12px',borderRadius:6,border:'none',background:'linear-gradient(135deg,#6366f1,#8b5cf6)',color:'white',fontSize:12,cursor:'pointer'}}>支付</button>
                    <button onClick={async()=>{await client.post(`/order/${o.orderNo}/cancel`);setOrders(orders.filter(x=>x.orderNo!==o.orderNo))}}
                      style={{padding:'4px 12px',borderRadius:6,border:'1px solid #e5e7eb',background:'white',color:'#ef4444',fontSize:12,cursor:'pointer'}}>取消</button>
                  </div>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>}
    </div>
  )
}