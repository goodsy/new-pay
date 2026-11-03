import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import { readCheckoutData } from './bootstrap'
import './styles.css'

const root = document.getElementById('root')
if (!root) throw new Error('#root element is missing')
const data = readCheckoutData(root)
createRoot(root).render(
  <StrictMode>
    <App data={data} />
  </StrictMode>,
)
