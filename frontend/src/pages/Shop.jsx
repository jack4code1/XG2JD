import { useState, useEffect } from 'react'
import client from '../api/client'

const formatTime = (value) => new Date(value).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })

export default function Shop({ shopId, onBack, onOrders }) {
  const [m, setM] = useState(null)
  const [coupons, setCoupons] = useState([])
  const [r, setR] = useState(null)
  const [product, setProduct] = useState(null)
  const [couponId, setCouponId] = useState('')
  const [buying, setBuying] = useState(false)

  const load = () => client.get('/merchant/list').then(({ data }) => setM((data || []).find(x => x.id == shopId) || null)).catch(() => {})
  useEffect(() => { load(); client.get('/order/user/coupons').then(({ data }) => setCoupons(Array.isArray(data) ? data : [])).catch(() => {}) }, [shopId])

  const go = async (cid) => {
    try {
      const { data } = await client.post('/seckill/execute', { couponId: cid, deviceFingerprint: 'fp-' + Math.random().toString(36).slice(2, 10) })
      if (data.success) {
        let message = '抢券成功，订单正在创建…'
        for (let attempt = 0; attempt < 5; attempt++) {
          await new Promise(resolve => setTimeout(resolve, 300))
          try {
            const result = await client.get('/seckill/result/' + data.orderNo)
            if (result.data?.success) { message = result.data.message || '抢券成功，订单已创建'; break }
          } catch { /* 订单仍在 MQ 队列中，继续轮询 */ }
        }
        setR({ ...data, message })
        load(); client.get('/order/user/coupons').then(({ data: owned }) => setCoupons(Array.isArray(owned) ? owned : []))
      } else setR(data)
    } catch { setR({ success: false, message: '系统繁忙，请稍后再试' }) }
  }

  const buy = async () => {
    if (!product) return
    setBuying(true)
    try {
      const { data } = await client.post('/product/' + product.id + '/purchase', couponId ? { couponId: Number(couponId) } : {})
      setProduct(null)
      setR({ success: true, message: `商品订单已创建，应付 ¥${Number(data.payableAmount).toFixed(2)}，请前往订单完成支付`, orderNo: data.orderNo, productOrder: true })
      load()
    } catch (e) { setR({ success: false, message: e.response?.data?.message || '下单失败，请稍后重试' }) }
    finally { setBuying(false) }
  }

  if (!m) return <div className="empty">正在打开店铺…</div>
  const shopCoupons = coupons.filter(c => c.merchantId === m.id)

  return <>
    <button className="back" onClick={onBack}>← 返回发现页</button>
    <section className="shop-hero"><div className="eyebrow">TODAY'S SHOP</div><h1>{m.shopName}</h1><p>{m.category || '精选好店'}　·　正在营业　·　{m.couponCount || 0} 张优惠券</p></section>

    <div className="section-head"><div><h2>店铺商品</h2><div className="muted">代金券不能单独支付，只能在商品结算时抵扣</div></div></div>
    <div className="product-grid">{(m.products || []).map(p => <article className="product-card" key={p.id}><div className="product-cover">{m.category === '餐饮' ? '🍜' : '👜'}</div><div className="product-body"><div className="product-kicker">店铺精选</div><h3>{p.name}</h3><p>{p.description || '精选商品，欢迎选购。'}</p><div className="product-bottom"><strong>¥{Number(p.price).toFixed(2)}</strong><span>库存 {p.remainStock}</span></div><button className="primary-btn product-buy" disabled={!p.remainStock} onClick={() => { setProduct(p); setCouponId('') }}>{p.remainStock ? '立即购买' : '已售罄'}</button></div></article>)}</div>

    <div className="section-head"><div><h2>限时好券</h2><div className="muted">领取后可在本店商品结算时抵扣</div></div></div>
    <div className="coupon-list">{(m.coupons || []).map(c => { const now = Date.now(); const start = new Date(c.startTime).getTime(); const end = new Date(c.endTime).getTime(); const remain = c.remainStock || 0; const total = c.totalStock || 100; const pct = Math.max(0, remain / total * 100); const paused = c.status === 3; const notStarted = now < start; const ended = now >= end || c.status === 2; const active = !paused && !notStarted && !ended && c.status === 1; const timeText = paused ? '活动已由商户暂停' : notStarted ? `将于 ${formatTime(c.startTime)} 开始` : ended ? `已于 ${formatTime(c.endTime)} 结束` : `进行中 · 截止 ${formatTime(c.endTime)}`; const buttonText = paused ? '已暂停' : notStarted ? '未开始' : ended ? '已结束' : remain > 0 ? '立即领' : '已领光'; return <div className="coupon" key={c.id}><div className="coupon-value">抵扣<br /><small>¥{Number(c.discountAmount || 0).toFixed(0)}</small></div><div className="coupon-info"><strong>{c.couponName}</strong><small>剩余 {remain} / {total}　·　每人限领 {c.perUserMax || 1} 张</small><span className="coupon-time">{timeText}</span><div className="stock"><i style={{ width: (100 - pct) + '%' }} /></div></div><button className="primary-btn" onClick={() => go(c.id)} disabled={!active || remain < 1}>{buttonText}</button></div> })}</div>

    {product && <div className="modal-mask" onClick={() => !buying && setProduct(null)}><div className="payment-modal" onClick={e => e.stopPropagation()}><div className="payment-brand">火</div><h3>确认购买</h3><p className="muted">{product.name}</p><div className="payment-amount">¥{Number(product.price).toFixed(2)}</div><label className="coupon-select-label">选择代金券（可选）</label><select className="input coupon-select" value={couponId} onChange={e => setCouponId(e.target.value)}><option value="">不使用代金券</option>{shopCoupons.map(c => <option key={c.id} value={c.id}>{c.name} · 抵扣 ¥{Number(c.discountAmount).toFixed(2)}</option>)}</select><p className="payment-tip">商品订单创建后，请到“我的订单”完成支付</p><button className="primary-btn payment-submit" onClick={buy} disabled={buying}>{buying ? '创建订单中…' : '确认下单'}</button><button className="logout payment-cancel" onClick={() => setProduct(null)} disabled={buying}>取消</button></div></div>}
    {r && <div className="modal-mask" onClick={() => setR(null)}><div className="modal" onClick={e => e.stopPropagation()}><div className="modal-icon">{r.success ? '🎉' : '😞'}</div><h3>{r.success ? '操作成功' : '操作失败'}</h3><p>{r.message}{r.success && r.userWeight ? <><br />本次分配权重：{r.userWeight}</> : null}</p>{r.productOrder ? <button className="primary-btn" onClick={onOrders}>去订单支付</button> : <button className="primary-btn" onClick={() => setR(null)}>知道了</button>}</div></div>}
  </>
}
