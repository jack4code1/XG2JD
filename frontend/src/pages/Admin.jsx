import { useEffect, useState } from 'react'
import client from '../api/client'
import { useAuth } from '../AuthContext'

const COUPON_STATUS = { 0: '未开始', 1: '运行中', 2: '已结束', 3: '已暂停' }

export default function Admin() {
  const { isLogin, role } = useAuth()
  const [couponForm, setCouponForm] = useState({ couponName: '', discountAmount: 20, totalStock: 100, perUserMax: 1, endHours: 24 })
  const [productForm, setProductForm] = useState({ name: '', description: '', price: 39.9, remainStock: 50 })
  const [shop, setShop] = useState(null)
  const [editingShop, setEditingShop] = useState(false)
  const [selectedProduct, setSelectedProduct] = useState(null)
  const [selectedCoupon, setSelectedCoupon] = useState(null)
  const [cacheDetail, setCacheDetail] = useState(null)
  const [cacheLoading, setCacheLoading] = useState(false)
  const [cacheStatus, setCacheStatus] = useState(null)
  const [versions, setVersions] = useState([])
  const [versionsLoading, setVersionsLoading] = useState(false)
  const [additionalStock, setAdditionalStock] = useState(0)
  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState('')

  const loadShop = () => client.get('/merchant/me')
    .then(({ data }) => setShop(data))
    .catch(error => setMessage('店铺加载失败：' + (error.response?.data?.message || error.message)))

  useEffect(() => {
    if (isLogin && role === 'MERCHANT') loadShop()
  }, [isLogin, role])

  if (!isLogin || role !== 'MERCHANT') return <div className="empty">请先登录商家账号</div>

  const createCoupon = async event => {
    event.preventDefault()
    setMessage('')
    try {
      const now = new Date()
      const end = new Date(now.getTime() + Number(couponForm.endHours) * 36e5)
      const { data } = await client.post('/coupon/create', {
        couponName: couponForm.couponName,
        discountAmount: Number(couponForm.discountAmount),
        totalStock: Number(couponForm.totalStock),
        remainStock: Number(couponForm.totalStock),
        startTime: now.toISOString(),
        endTime: end.toISOString(),
        perUserMax: Number(couponForm.perUserMax),
        status: 1,
      })
      setShop(current => ({ ...current, coupons: [data, ...(current?.coupons || [])], couponCount: (current?.couponCount || 0) + 1 }))
      setCouponForm({ couponName: '', discountAmount: 20, totalStock: 100, perUserMax: 1, endHours: 24 })
      setMessage('已创建 ' + data.couponName + ' · 库存 ' + data.totalStock)
    } catch (error) {
      setMessage('创建失败：' + (error.response?.data?.message || error.message))
    }
  }

  const createProduct = async event => {
    event.preventDefault()
    try {
      const { data } = await client.post('/product/create', {
        ...productForm,
        price: Number(productForm.price),
        remainStock: Number(productForm.remainStock),
      })
      setShop(current => ({ ...current, products: [data, ...(current?.products || [])] }))
      setProductForm({ name: '', description: '', price: 39.9, remainStock: 50 })
      setMessage('已创建商品 ' + data.name)
    } catch (error) {
      setMessage('商品创建失败：' + (error.response?.data?.message || error.message))
    }
  }

  const saveShop = async event => {
    event.preventDefault()
    try {
      const { data } = await client.put('/merchant/' + shop.id, shop)
      setShop(current => ({ ...current, ...data }))
      setEditingShop(false)
      setMessage('店铺信息已保存')
    } catch (error) {
      setMessage('保存失败：' + (error.response?.data?.message || error.message))
    }
  }

  const persistProduct = async (status = selectedProduct.status, close = true) => {
    setSaving(true)
    try {
      const { data } = await client.put('/product/' + selectedProduct.id, {
        name: selectedProduct.name,
        description: selectedProduct.description || '',
        price: Number(selectedProduct.price),
        remainStock: Number(selectedProduct.remainStock),
        status,
      })
      setShop(current => ({ ...current, products: current.products.map(item => item.id === data.id ? data : item) }))
      setSelectedProduct(close ? null : data)
      setMessage('商品「' + data.name + '」已保存')
    } catch (error) {
      setMessage('商品保存失败：' + (error.response?.data?.message || error.message))
    } finally {
      setSaving(false)
    }
  }

  const persistCoupon = async (status = selectedCoupon.status, close = true, stockToAdd = additionalStock) => {
    setSaving(true)
    try {
      const { data } = await client.put('/coupon/' + selectedCoupon.id, {
        couponName: selectedCoupon.couponName,
        couponDesc: selectedCoupon.couponDesc || '',
        discountAmount: Number(selectedCoupon.discountAmount || 0),
        perUserMax: Number(selectedCoupon.perUserMax),
        endTime: selectedCoupon.endTime,
        additionalStock: Number(stockToAdd || 0),
        status,
      })
      setShop(current => ({ ...current, coupons: current.coupons.map(item => item.id === data.id ? data : item) }))
      setAdditionalStock(0)
      setSelectedCoupon(close ? null : data)
      if (!close) loadCouponDetail(data.id)
      setMessage('优惠券「' + data.couponName + '」已保存并同步 Redis')
    } catch (error) {
      setMessage('优惠券保存失败：' + (error.response?.data?.message || error.message))
    } finally {
      setSaving(false)
    }
  }

  const loadCouponDetail = async couponId => {
    setCacheLoading(true)
    try {
      const { data } = await client.get('/coupon/' + couponId)
      setCacheDetail(data)
      const status = await client.get('/coupon/' + couponId + '/cache-status')
      setCacheStatus(status.data)
    } catch (error) {
      setMessage('活动快照读取失败：' + (error.response?.data?.message || error.message))
    } finally {
      setCacheLoading(false)
    }
  }

  const loadVersions = async couponId => {
    setVersionsLoading(true)
    try {
      const { data } = await client.get('/coupon/' + couponId + '/versions')
      setVersions(Array.isArray(data) ? data : [])
    } catch (error) {
      setMessage('活动版本读取失败：' + (error.response?.data?.message || error.message))
    } finally {
      setVersionsLoading(false)
    }
  }

  const rollbackCoupon = async version => {
    if (!selectedCoupon || !window.confirm(`确认回滚到活动版本 v${version}？`)) return
    setSaving(true)
    try {
      const { data } = await client.post(`/coupon/${selectedCoupon.id}/rollback/${version}`)
      setSelectedCoupon(data)
      setShop(current => ({ ...current, coupons: current.coupons.map(item => item.id === data.id ? data : item) }))
      await Promise.all([loadVersions(data.id), loadCouponDetail(data.id)])
      setMessage(`活动已回滚并发布为新版本，当前基于 v${version} 配置。`)
    } catch (error) {
      setMessage('活动回滚失败：' + (error.response?.data?.message || error.message))
    } finally {
      setSaving(false)
    }
  }

  const drill = async scenario => {
    try {
      await client.post('/drill/start?scenario=' + scenario)
      await new Promise(resolve => setTimeout(resolve, 3000))
      const { data } = await client.post('/drill/recover')
      setMessage(scenario + ' 演练完成 · ' + data.durationMs + 'ms')
    } catch {
      setMessage('演练失败')
    }
  }

  return <>
    <div className="section-head"><div><h2>{shop?.shopName || '商家工作台'}</h2><div className="muted">管理当前店铺、商品和优惠券活动</div></div></div>
    {message && <div className={'order-message ' + (message.includes('失败') ? 'manage-error' : '')}>{message}</div>}

    {shop && <div className="panel" style={{ marginBottom: 16 }}>
      <div className="section-head"><div><h3>店铺资料</h3><div className="muted">店铺 ID：{shop.id}</div></div><button className="logout" onClick={() => setEditingShop(!editingShop)}>{editingShop ? '取消' : '编辑资料'}</button></div>
      {editingShop ? <form onSubmit={saveShop}>
        <div className="form-grid"><Field label="店铺名称"><input className="input" value={shop.shopName || ''} onChange={event => setShop({ ...shop, shopName: event.target.value })} /></Field><Field label="分类"><input className="input" value={shop.category || ''} onChange={event => setShop({ ...shop, category: event.target.value })} /></Field></div>
        <Field label="店铺介绍"><textarea className="input" value={shop.shopDesc || ''} onChange={event => setShop({ ...shop, shopDesc: event.target.value })} /></Field>
        <button className="primary-btn" type="submit">保存店铺资料</button>
      </form> : <p className="muted">{shop.category || '其他'} · {shop.shopDesc || '还没有店铺介绍'}</p>}
    </div>}

    <div className="form-grid admin-grid">
      <div className="panel"><h3>创建商品</h3><form onSubmit={createProduct}>
        <Field label="商品名称"><input className="input" required value={productForm.name} onChange={event => setProductForm({ ...productForm, name: event.target.value })} placeholder="例如：招牌牛肉饭" /></Field>
        <Field label="商品介绍"><input className="input" value={productForm.description} onChange={event => setProductForm({ ...productForm, description: event.target.value })} placeholder="一句话介绍商品" /></Field>
        <div className="form-grid compact-grid"><Field label="价格"><input className="input" type="number" min="0.01" step="0.01" value={productForm.price} onChange={event => setProductForm({ ...productForm, price: event.target.value })} /></Field><Field label="库存"><input className="input" type="number" min="0" value={productForm.remainStock} onChange={event => setProductForm({ ...productForm, remainStock: event.target.value })} /></Field></div>
        <button className="primary-btn" type="submit">上架商品</button>
      </form></div>

      <ManageList title="我的商品" subtitle="点击商品进入管理界面" empty="还没有商品">
        {(shop?.products || []).map(product => <button className="manage-item" key={product.id} onClick={() => setSelectedProduct({ ...product })}>
          <div><strong>{product.name}</strong><span>¥{Number(product.price).toFixed(2)} · 库存 {product.remainStock}</span></div>
          <div className="manage-item-side"><b className={'status-badge ' + (product.status === 1 ? 'status-paid' : '')}>{product.status === 1 ? '销售中' : '已下架'}</b><i>›</i></div>
        </button>)}
      </ManageList>
    </div>

    <div className="form-grid admin-grid">
      <div className="panel"><h3>创建一张优惠券</h3><form onSubmit={createCoupon}>
        <Field label="优惠券名称"><input className="input" required value={couponForm.couponName} onChange={event => setCouponForm({ ...couponForm, couponName: event.target.value })} placeholder="例如：新客专享立减券" /></Field>
        <div className="coupon-create-grid">
          <Field label="优惠金额"><input className="input" type="number" min="0" step="0.01" value={couponForm.discountAmount} onChange={event => setCouponForm({ ...couponForm, discountAmount: event.target.value })} /></Field>
          <Field label="总库存"><input className="input" type="number" min="1" value={couponForm.totalStock} onChange={event => setCouponForm({ ...couponForm, totalStock: event.target.value })} /></Field>
          <Field label="每人限领"><input className="input" type="number" min="1" max="5" value={couponForm.perUserMax} onChange={event => setCouponForm({ ...couponForm, perUserMax: event.target.value })} /></Field>
          <Field label="有效时长（小时）"><input className="input" type="number" min="1" value={couponForm.endHours} onChange={event => setCouponForm({ ...couponForm, endHours: event.target.value })} /></Field>
        </div>
        <button className="primary-btn" type="submit">创建活动并预热 Redis</button>
      </form></div>

      <ManageList title="我的优惠券" subtitle={`共 ${shop?.couponCount || 0} 张 · 点击进入管理`} empty="还没有优惠券">
        {(shop?.coupons || []).map(coupon => <button className="manage-item" key={coupon.id} onClick={() => { setSelectedCoupon({ ...coupon }); setAdditionalStock(0); setCacheDetail(null); setCacheStatus(null); setVersions([]); loadVersions(coupon.id) }}>
          <div><strong>{coupon.couponName}</strong><span>抵扣 ¥{Number(coupon.discountAmount || 0).toFixed(2)} · 库存 {coupon.remainStock} / {coupon.totalStock}</span></div>
          <div className="manage-item-side"><b className={'status-badge ' + (coupon.status === 1 ? 'status-paid' : '')}>{COUPON_STATUS[coupon.status] || '未知'}</b><i>›</i></div>
        </button>)}
      </ManageList>
    </div>

    <div className="panel" style={{ marginTop: 16 }}><h3>故障演练</h3><p className="muted">在真实流量前验证降级和恢复能力。</p><div className="drill-grid">{[['redis', 'Redis'], ['mq', 'RabbitMQ'], ['db', '数据库'], ['network', '网络']].map(([scenario, name]) => <button className="logout" key={scenario} onClick={() => drill(scenario)}>演练 {name}</button>)}</div></div>

    {selectedProduct && <ManageModal title="商品管理" subtitle={`商品 ID：${selectedProduct.id}`} onClose={() => !saving && setSelectedProduct(null)}>
      <div className="manage-modal-status"><span>当前状态</span><b className="status-badge">{selectedProduct.status === 1 ? '销售中' : '已下架'}</b></div>
      <Field label="商品名称"><input className="input" value={selectedProduct.name} onChange={event => setSelectedProduct({ ...selectedProduct, name: event.target.value })} /></Field>
      <Field label="商品介绍"><textarea className="input manage-textarea" value={selectedProduct.description || ''} onChange={event => setSelectedProduct({ ...selectedProduct, description: event.target.value })} /></Field>
      <div className="form-grid compact-grid"><Field label="价格"><input className="input" type="number" min="0.01" step="0.01" value={selectedProduct.price} onChange={event => setSelectedProduct({ ...selectedProduct, price: event.target.value })} /></Field><Field label="可售库存"><input className="input" type="number" min="0" value={selectedProduct.remainStock} onChange={event => setSelectedProduct({ ...selectedProduct, remainStock: event.target.value })} /></Field></div>
      <div className="manage-modal-actions"><button className="logout" disabled={saving} onClick={() => persistProduct(selectedProduct.status === 1 ? 0 : 1, false)}>{selectedProduct.status === 1 ? '下架商品' : '恢复上架'}</button><button className="primary-btn" disabled={saving} onClick={() => persistProduct()}>{saving ? '保存中…' : '保存修改'}</button></div>
    </ManageModal>}

    {selectedCoupon && <ManageModal title="优惠券管理" subtitle={`优惠券 ID：${selectedCoupon.id}`} onClose={() => !saving && setSelectedCoupon(null)}>
      <div className="manage-modal-status"><span>活动状态</span><b className="status-badge">{COUPON_STATUS[selectedCoupon.status] || '未知'}</b></div>
      <Field label="优惠券名称"><input className="input" value={selectedCoupon.couponName} onChange={event => setSelectedCoupon({ ...selectedCoupon, couponName: event.target.value })} /></Field>
      <Field label="活动说明"><textarea className="input manage-textarea" value={selectedCoupon.couponDesc || ''} onChange={event => setSelectedCoupon({ ...selectedCoupon, couponDesc: event.target.value })} /></Field>
      <div className="form-grid compact-grid"><Field label="优惠金额"><input className="input" type="number" min="0" step="0.01" value={selectedCoupon.discountAmount || 0} onChange={event => setSelectedCoupon({ ...selectedCoupon, discountAmount: event.target.value })} /></Field><Field label="每人限领"><input className="input" type="number" min="1" max="5" value={selectedCoupon.perUserMax} onChange={event => setSelectedCoupon({ ...selectedCoupon, perUserMax: event.target.value })} /></Field></div>
      <Field label="结束时间"><input className="input" type="datetime-local" value={dateTimeValue(selectedCoupon.endTime)} onChange={event => setSelectedCoupon({ ...selectedCoupon, endTime: event.target.value })} /></Field>
      <div className="stock-manage"><div><span>当前实时库存</span><strong>{selectedCoupon.remainStock} / {selectedCoupon.totalStock}</strong></div><Field label="追加库存"><input className="input" type="number" min="0" max="5000" value={additionalStock} onChange={event => setAdditionalStock(event.target.value)} /></Field></div>
      <div className="manage-modal-status" style={{ marginTop: 16 }}>
        <div><span>活动配置快照</span><div className="muted">详情读取走版本化 L1/L2 缓存，库存不进入本地缓存</div></div>
        <button className="logout" disabled={cacheLoading} onClick={() => loadCouponDetail(selectedCoupon.id)}>{cacheLoading ? '读取中…' : '查看快照'}</button>
      </div>
      {cacheDetail && <div className="proposal-grid" style={{ borderTop: '1px solid #eee' }}>
        <div><span>缓存版本</span><strong>v{cacheDetail.version}</strong></div>
        <div><span>活动生命周期</span><strong>{cacheDetail.lifecycle}</strong></div>
        <div><span>限领规则</span><strong>每人 {cacheDetail.perUserMax} 张</strong></div>
      </div>}
      {cacheStatus && <div className="proposal-grid" style={{ borderTop: 0 }}>
        <div><span>热点状态</span><strong>{cacheStatus.hot ? 'HOT · L1 加速中' : 'NORMAL · Redis L2'}</strong></div>
        <div><span>L1 命中率</span><strong>{(Number(cacheStatus.l1HitRate || 0) * 100).toFixed(1)}%</strong></div>
        <div><span>L1 淘汰次数</span><strong>{cacheStatus.l1Evictions}</strong></div>
      </div>}
      <div className="manage-modal-status" style={{ marginTop: 16 }}>
        <div><span>活动版本历史</span><div className="muted">每次发布、编辑、自动流转和回滚均保留不可变快照</div></div>
        <button className="logout" disabled={versionsLoading} onClick={() => loadVersions(selectedCoupon.id)}>{versionsLoading ? '读取中…' : '刷新版本'}</button>
      </div>
      {versions.length > 0 && <div className="timeline" style={{ marginTop: 12, paddingTop: 12 }}>
        {versions.slice(0, 6).map(item => <div className="timeline-item" key={item.id}><i /><div><strong>v{item.versionNo} · {item.action}</strong><span>{item.createdAt ? new Date(item.createdAt).toLocaleString('zh-CN') : '刚刚'}</span>{item.versionNo !== selectedCoupon.version && <button className="order-detail-btn" disabled={saving} onClick={() => rollbackCoupon(item.versionNo)}>回滚到此版本</button>}</div></div>)}
      </div>}
      <div className="manage-modal-actions"><button className="logout" disabled={saving} onClick={() => persistCoupon(selectedCoupon.status === 1 ? 3 : 1, false, 0)}>{selectedCoupon.status === 1 ? '暂停活动' : '启用 / 恢复'}</button><button className="primary-btn" disabled={saving} onClick={() => persistCoupon()}>{saving ? '同步中…' : '保存并同步 Redis'}</button></div>
    </ManageModal>}
  </>
}

function Field({ label, children }) {
  return <div className="field"><label>{label}</label>{children}</div>
}

function ManageList({ title, subtitle, empty, children }) {
  const items = Array.isArray(children) ? children : children ? [children] : []
  return <div className="panel manage-list"><h3>{title}</h3><div className="muted manage-list-tip">{subtitle}</div>{items.length ? <div>{items}</div> : <div className="empty">{empty}</div>}</div>
}

function ManageModal({ title, subtitle, onClose, children }) {
  return <div className="modal-mask" onClick={onClose}><div className="manage-modal" onClick={event => event.stopPropagation()}><div className="detail-heading"><div><span className="eyebrow">MANAGEMENT</span><h3>{title}</h3><div className="muted">{subtitle}</div></div><button className="detail-close" onClick={onClose}>×</button></div>{children}</div></div>
}

function dateTimeValue(value) {
  return value ? String(value).slice(0, 16) : ''
}
