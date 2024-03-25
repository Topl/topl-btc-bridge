import React from 'react'
import ReactDOM from 'react-dom/client'
import {
  createBrowserRouter,
  Navigate,
  RouterProvider,
} from "react-router-dom"
import Frame from './Frame.tsx'
import './index.css'
import StartSession from './views/StartSession.tsx'
import WaitingForBTC from './views/WaitingForBTC.tsx'

const router = createBrowserRouter([
  {
    path: "/",
    element: <Frame />,
    children: [
      {
        path: "/pegin/startSession",
        element: <StartSession />,
      },
      {
        path: "/pegin/waitingForFunds",
        element: <WaitingForBTC />,
      },
      {
        path: "/",
        element: <Navigate to="/pegin/startSession" replace={true} />,
      },
    ]
  },
]);


ReactDOM.createRoot(document.getElementById('wrapper')!).render(
  <React.StrictMode>
    <RouterProvider router={router} />
  </React.StrictMode>
)
