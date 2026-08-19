import { useState, useEffect } from 'react'
import client from '../api/client'

export default function Shop({ shopId, onBack }) {
  const [merchant, setMerchant] = useState(null)
  const [result, setResult] = useState(null)

  useEffect(() => {
    client.get('/merchant/list').then(({data}) => {
      setMerchant((data||[]).find(m => m.id == shopId) || null)
    }).catch(()=>{})
  }, [shopId])

  const seckill = async (couponId) => {
    try {
      const { data } = await client.post('/seckill/execute', {
        couponId, deviceFingerprint:'fp-'+Math.random().toString(36).slice(2,10)
      })
      setResult(data)
      if (data.success) {
        client.get('/merchant/list').then(({data:d}) => {
          setMerchant((d||[]).find(m => m.id == shopId) || null)
        }).catch(()=>{})
      }
    } catch(e) { setResult({success:false, message:'系统繁忙'}) }
  }

  if (!merchant) return <div className="text-center py-12"><span className="loading loading-spinner loading-lg" /></div>

  return (
    <div>
      <button onClick={onBack} className="btn btn-ghost btn-sm mb-4">← 返回商城</button>
      <div className="card bg-base-100 shadow-lg mb-6">
        <div className="card-body"><h1 className="text-2xl font-bold">{merchant.shopName}</h1></div>
      </div>
      <div className="grid gap-4">
        {(merchant.coupons||[]).map(c => {
          const remain=c.remainStock||0, total=c.totalStock||100, active=new Date(c.endTime).getTime()>Date.now()
          return (
            <div key={c.id} className="card card-side bg-base-100 shadow-lg">
              <div className="card-body">
                <h3 className="card-title">{c.couponName}</h3>
                <div className="flex items-center gap-4">
                  <progress className="progress progress-primary w-40" value={total-remain} max={total} />
                  <span className="text-sm">剩余 {remain}/{total}</span>
                </div>
              </div>
              <div className="card-actions items-center p-4">
                <button onClick={()=>seckill(c.id)} disabled={!active} className={`btn ${active?'btn-primary':'btn-disabled'}`}>
                  {active?'⚡ 抢券':'已结束'}
                </button>
              </div>
            </div>
          )
        })}
      </div>
      {result && (
        <div className="modal modal-open">
          <div className="modal-box text-center">
            <div className="text-6xl mb-4">{result.success?'🎉':'😞'}</div>
            <h3 className={`text-xl font-bold ${result.success?'text-success':'text-error'}`}>{result.success?'抢券成功！':'抢券失败'}</h3>
            <p className="py-2">{result.message}</p>
            {result.success && <p className="text-sm text-base-content/60">权重:{result.userWeight}</p>}
            <div className="modal-action justify-center"><button onClick={()=>setResult(null)} className="btn btn-primary">确定</button></div>
          </div>
        </div>
      )}
    </div>
  )
}