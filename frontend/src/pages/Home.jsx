import { useState, useEffect } from 'react'
import { useAuth } from '../AuthContext'
import client from '../api/client'

const ICONS = { 餐饮:'🍜', 服饰:'👜', 数码:'🎧', 美妆:'💄', 其他:'🎁' }
const TABS = ['推荐', '今日上新', '餐饮', '数码', '美妆', '服饰']

export default function Home({ onShopClick }) {
  const { isLogin } = useAuth(); const [list, setList] = useState([]); const [tab, setTab] = useState('推荐')
  useEffect(() => { isLogin && client.get('/merchant/list').then(({data})=>setList(Array.isArray(data)?data:[])).catch(()=>{}) }, [isLogin])
  const filtered = list.filter(m => tab === '推荐' || tab === '今日上新' || m.category === tab)
  return <>
    <section className="hero-panel"><div className="hero-copy"><div className="eyebrow">SECKILL DAILY · 08.19</div><h1>今天，<br/>抢点好的。</h1><p>精选好店限时放券，先到先得，把心动带回家。</p><div className="hero-stats"><div className="hero-stat"><strong>{list.length || '—'}</strong><span>正在营业的店铺</span></div><div className="hero-stat"><strong>100%</strong><span>限时真实库存</span></div></div></div></section>
    <div className="section-head"><div><h2>发现好券</h2><div className="muted">每天都有新惊喜</div></div><div className="filters">{TABS.map(x=><button className={'filter ' + (tab===x?'active':'')} key={x} onClick={()=>setTab(x)}>{x}</button>)}</div></div>
    {filtered.length ? <div className="feed">{filtered.map((m, i) => <article className="note-card" key={m.id} onClick={()=>onShopClick(m.id)}><div className={'note-art art-' + ({餐饮:'food',服饰:'fashion',数码:'tech',美妆:'beauty'}[m.category]||'other')}><span>{ICONS[m.category]||ICONS.其他}</span><span className="note-badge">限时放券</span></div><div className="note-body"><h3 className="note-title">{m.shopName} · {m.couponCount || 0} 张好券等你来抢</h3><div className="note-meta"><span>{m.category || '精选好店'}　·　今日推荐</span><span>♡ {18 + i * 7}</span></div></div></article>)}</div> : <div className="empty">还没有店铺内容，去商家端创建第一张优惠券吧</div>}
  </>
}
