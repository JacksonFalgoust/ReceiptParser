import { Routes, Route } from 'react-router'
import UploadReceipt from './routes/UploadReceipt'
import ReviewItems from './routes/ReviewItems'
import BillRoom from './routes/BillRoom'
import Summary from './routes/Summary'

function App() {
  return (
    <Routes>
      <Route path="/" element={<UploadReceipt />} />
      <Route path="/review" element={<ReviewItems />} />
      <Route path="/bill/:roomCode" element={<BillRoom />} />
      <Route path="/bill/:roomCode/summary" element={<Summary />} />
    </Routes>
  )
}

export default App
