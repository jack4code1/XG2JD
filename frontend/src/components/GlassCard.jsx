import { motion } from 'framer-motion'

export default function GlassCard({ children, className = '', ...props }) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.4 }}
      className={`glass rounded-2xl p-6 ${className}`}
      {...props}
    >
      {children}
    </motion.div>
  )
}