import React from 'react'
import ReactDOM from 'react-dom/client'
import {
  createBrowserRouter,
  Navigate,
  RouterProvider,
} from "react-router-dom"
import Frame from './Frame.tsx'
import './index.css'
import App from './App.tsx'

const router = createBrowserRouter([
  {
    path: "/",
    element: <Frame />,
    children: [
      {
        path: "/pegin",
        element: <App />,
      },
      {
        path: "/",
        element: <Navigate to="/pegin" replace={true} />,
      },
    ]
  },
]);


ReactDOM.createRoot(document.getElementById('wrapper')!).render(
  <React.StrictMode>
    <RouterProvider router={router} />
  </React.StrictMode>
)
