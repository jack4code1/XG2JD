import { useState, useEffect } from 'react'
import { useAuth } from '../AuthContext'
import client from '../api/client'

const ICONS = { '餐饮':'🍔','服饰':'👗','数码':'📱','美妆':'💄','其他':'🛒' }

export default function Home({ onShopClick }) {
  const { isLogin } = useAuth()
  const [merchants, setMerchants] = useState([])

  useEffect(() => {
    if (!isLogin) return
    client.get('/merchant/list').then(({data}) => setMerchants(Array.isArray(data)?data:[])).catch(()=>{})
  }, [isLogin])

  return (
    <div>
      <div className="hero bg-base-200 rounded-box mb-8 py-12">
        <div className="hero-content text-center">
          <div><h1 className="text-4xl font-bold gradient-text">🔥 限时秒杀</h1>
          <p className="py-4 text-base-content/60">精选商家 · 超值优惠券 · 手慢无</p></div>
        </div>
      </div>
      <h2 className="text-2xl font-bold mb-6">🏬 精选商家</h2>
      <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-4">
        {merchants.map(m => (
          <div key={m.id} onClick={() => onShopClick(m.id)}
            className="card bg-base-100 shadow-lg hover:shadow-xl transition-all hover:-translate-y-1 cursor-pointer">
            <div className="card-body items-center text-center p-6">
              <span className="text-5xl mb-3">{ICONS[m.category]||'🛒'}</span>
              <h3 className="card-title text-base">{m.shopName}</h3>
              <div className="badge badge-outline">{m.category||'其他'}</div>
              <p className="text-sm text-base-content/60">{m.couponCount} 张券</p>
            </div>
          </div>
        ))}
        {merchants.length===0 && <div className="col-span-full text-center py-12 text-base-content/40"><p>暂无商家</p></div>}
      </div>
    </div>
  )
}