import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../AuthContext'
import client from '../api/client'

const STATUS = { CREATED:'待支付', PAYING:'支付中', PAID:'已支付', USED:'已使用', CANCELED:'已取消', EXPIRED:'已过期' }

export default function Orders() {
  const { isLogin, user } = useAuth()
  const [orders, setOrders] = useState([])
  const nav = useNavigate()

  useEffect(() => {
    if (!isLogin) { nav('/login'); return }
    client.get('/merchant/list').then(async ({data: merchants}) => {
      const all = []
      for (const m of (merchants||[])) {
        for (const c of (m.coupons||[])) {
          try {
            // Get orders for this coupon's user
            const { data } = await client.get(`/order/user/${c.id}`)
            if (Array.isArray(data)) all.push(...data.map(o => ({...o, couponName: c.couponName, shopName: m.shopName})))
          } catch {}
        }
      }
      setOrders(all)
    }).catch(()=>{})
  }, [isLogin])

  const pay = async (orderNo) => {
    try { await client.post(`/order/${orderNo}/pay`); alert('支付成功'); setOrders(orders.map(o=>o.orderNo===orderNo?{...o,status:'PAID'}:o)) }
    catch { alert('支付失败') }
  }
  const cancel = async (orderNo) => {
    try { await client.post(`/order/${orderNo}/cancel`); setOrders(orders.filter(o=>o.orderNo!==orderNo)) }
    catch { alert('取消失败') }
  }

  return (
    <div>
      <h2 className="text-2xl font-bold mb-6">📋 我的订单</h2>
      {orders.length === 0 ? (
        <div className="text-center py-12 text-base-content/40">
          <p className="text-lg mb-2">暂无订单</p>
          <a href="/" className="btn btn-primary btn-sm">去商城逛逛</a>
        </div>
      ) : (
        <div className="overflow-x-auto">
          <table className="table">
            <thead>
              <tr><th>订单号</th><th>商家</th><th>优惠券</th><th>状态</th><th>操作</th></tr>
            </thead>
            <tbody>
              {orders.map(o => (
                <tr key={o.orderNo}>
                  <td className="font-mono text-xs">{o.orderNo?.slice(0,14)}...</td>
                  <td>{o.shopName || '-'}</td>
                  <td>{o.couponName || '-'}</td>
                  <td><span className={`badge ${o.status==='CREATED'?'badge-info':o.status==='PAID'?'badge-success':'badge-ghost'}`}>{STATUS[o.status]||o.status}</span></td>
                  <td>
                    {o.status === 'CREATED' && (
                      <div className="flex gap-1">
                        <button onClick={() => pay(o.orderNo)} className="btn btn-xs btn-primary">支付</button>
                        <button onClick={() => cancel(o.orderNo)} className="btn btn-xs btn-ghost">取消</button>
                      </div>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}