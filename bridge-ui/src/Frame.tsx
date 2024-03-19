import { Link, Outlet } from 'react-router-dom';



function Frame() {

  return (
    <>
      <div className="border-end bg-white" id="sidebar-wrapper">
        <div className="sidebar-heading border-bottom bg-light">Topl-BTC Bridge</div>
        <div className="list-group list-group-flush">
          <Link className="list-group-item list-group-item-action list-group-item-light p-3" to={`/pegin`}>Peg-in</Link>
        </div>
      </div>
      <div id="page-content-wrapper">
        <nav className="navbar navbar-expand-lg navbar-light bg-light border-bottom">
          <ul className="navbar-nav">
            <li className="nav-item">
              <a className="nav-link active" aria-current="page" href="#">Bridge</a>
            </li>
          </ul>
        </nav>
        <div className='container'>
          <Outlet />
        </div>
      </div>
    </>
  )

}

export default Frame;