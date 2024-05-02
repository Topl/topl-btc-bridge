import ReactDOM from 'react-dom/client'
import {
  createBrowserRouter,
  Navigate,
  RouterProvider,
} from "react-router-dom"
import Frame from './Frame.tsx'
import './index.css'
import PeginView from './views/PeginView.tsx'

const router = createBrowserRouter([
  {
    path: "/",
    element: <Frame />,
    children: [
      {
        path: "/pegin",
        element: <PeginView  />,
      },
      {
        path: "/",
        element: <Navigate to="/pegin" replace={true} />,
      },
    ]
  },
]);


ReactDOM.createRoot(document.getElementById('wrapper')!).render(
    <RouterProvider router={router} />
)
