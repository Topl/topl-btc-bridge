
const style0 = {
  width: '0%'
};


function BTCWasNotSent(): JSX.Element {
  return (
    <>
      <div className="card-body">
        <div className="progress" role="progressbar" aria-label="Status" aria-valuenow={75} aria-valuemin={0} aria-valuemax={100}>
          <div className="progress-bar bg-success" style={style0}>0%</div>
        </div>
        <div className="row">
          <form className='row g-3'>
            <div className="row">
              <div className="mb-3">
                <div className="d-flex align-items-center">
                  <strong role="status">BTC was not received, the peg-in was cancelled</strong>
                </div>
              </div>
            </div>
          </form>
        </div>
      </div>
    </>
  );
}

export default BTCWasNotSent;