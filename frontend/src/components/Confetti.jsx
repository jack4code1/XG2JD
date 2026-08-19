import { useEffect, useRef } from 'react'

export default function Confetti({ active }) {
  const canvasRef = useRef(null)

  useEffect(() => {
    if (!active) return
    const c = canvasRef.current
    const ctx = c.getContext('2d')
    c.width = window.innerWidth
    c.height = window.innerHeight
    const particles = Array.from({ length: 80 }, () => ({
      x: Math.random() * c.width,
      y: -10,
      vx: (Math.random() - 0.5) * 8,
      vy: Math.random() * 6 + 2,
      s: Math.random() * 6 + 2,
      color: ['#6366f1','#8b5cf6','#10b981','#f59e0b','#ef4444'][Math.floor(Math.random()*5)]
    }))
    let frame
    function draw() {
      ctx.clearRect(0, 0, c.width, c.height)
      particles.forEach(p => {
        p.x += p.vx; p.y += p.vy; p.vy += 0.15
        ctx.fillStyle = p.color; ctx.beginPath()
        ctx.arc(p.x, p.y, p.s, 0, Math.PI*2); ctx.fill()
      })
      if (particles.some(p => p.y < c.height + 20)) frame = requestAnimationFrame(draw)
    }
    draw()
    return () => cancelAnimationFrame(frame)
  }, [active])

  if (!active) return null
  return <canvas ref={canvasRef} className="fixed inset-0 pointer-events-none z-50" />
}