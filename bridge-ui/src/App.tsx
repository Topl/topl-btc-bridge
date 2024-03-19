import './App.css';

function App() {

  return (
    <>
      <h1 className="mt-4">Peg-in</h1>
      <div className='row g-3'>
        <div className="col"></div>
        <div className="col">
          <div className="card">
            <form className='row g-3'>
              <div className="card-body">
                <div className="row">
                  <div className="mb-3">
                    <label htmlFor="hash" className="form-label">SHA-256 Hash</label>
                    <input type="text" className="form-control" id="hash" placeholder="497a39b618484855ebb5a2cabf6ee52ff092e7c17f8bfe79313529f9774f83a2" />
                  </div>
                  <div className="mb-3">
                    <button type="submit" className="btn btn-primary mb-3">Start Session</button>
                  </div>
                </div>
              </div>
            </form>
            <form className='row g-3'>
              <div className="mb-3">
                <label htmlFor="escrowAddress" className="form-label">Escrow Address</label>
                <input type="text" className="form-control" id="escrowAddress" disabled />
              </div>
            </form>
          </div></div>
        <div className="col"></div>
      </div>
    </>
  )
}

export default App
