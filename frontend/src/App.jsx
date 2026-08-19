import { useState } from 'react'
import UserTab from './pages/UserTab'
import MerchantTab from './pages/MerchantTab'
import AiTab from './pages/AiTab'

const TABS = [
  { key: 'user', label: '👤 用户端', component: UserTab },
  { key: 'merchant', label: '🏪 商家端', component: MerchantTab },
  { key: 'ai', label: '🤖 AI运营', component: AiTab },
]

export default function App() {
  const [tab, setTab] = useState('user')

  const TabComponent = TABS.find(t => t.key === tab)?.component

  return (
    <div className="max-w-5xl mx-auto px-4 py-6">
      {/* Header */}
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-bold gradient-text">🔥 高并发秒杀系统</h1>
        <div className="flex gap-1 glass rounded-xl p-1">
          {TABS.map(t => (
            <button
              key={t.key}
              onClick={() => setTab(t.key)}
              className={`px-4 py-2 rounded-lg text-sm font-medium transition-all ${
                tab === t.key
                  ? 'gradient-btn text-white shadow-lg'
                  : 'text-gray-400 hover:text-white'
              }`}
            >
              {t.label}
            </button>
          ))}
        </div>
      </div>

      {/* Tab Content */}
      <div className="min-h-[80vh]">
        {TabComponent && <TabComponent />}
      </div>

      {/* Footer */}
      <div className="text-center text-xs text-gray-600 mt-8 pb-4">
        Spring AI + DeepSeek · React + Vite + TailwindCSS · QPS 359 P50 98ms
      </div>
    </div>
  )
}