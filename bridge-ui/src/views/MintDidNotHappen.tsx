
const style0 = {
  width: '0%'
};


function MintDidNotHappen(): JSX.Element {
  return (
    <>
      <div className="card-body">
        <div className="progress" role="progressbar" aria-label="Status" aria-valuenow={0} aria-valuemin={0} aria-valuemax={100}>
          <div className="progress-bar bg-success" style={style0}>0%</div>
        </div>
        <div className="row">
          <form className='row g-3'>
            <div className="row">
              <div className="mb-3">
                <div className="d-flex align-items-center">
                  <strong role="status">The minting did not happen. You can recover the BTC from the escrow address.</strong>
                </div>
              </div>
            </div>
          </form>
        </div>
      </div>
    </>
  );
}

export default MintDidNotHappen;