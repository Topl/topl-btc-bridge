
const style100 = {
  width: '100%'
};


function WaitingForClaim(): JSX.Element {
  return (
    <>
      <div className="card-body">
        <div className="progress" role="progressbar" aria-label="Status" aria-valuenow={75} aria-valuemin={0} aria-valuemax={100}>
          <div className="progress-bar bg-success" style={style100}>100%</div>
        </div>
        <div className="row">
          <form className='row g-3'>
            <div className="row">
              <div className="mb-3">
                <div className="d-flex align-items-center">
                  <strong role="status">The pegin process was completed successfully</strong>
                </div>
              </div>
            </div>
          </form>
        </div>
      </div>
    </>
  );
}

export default WaitingForClaim;