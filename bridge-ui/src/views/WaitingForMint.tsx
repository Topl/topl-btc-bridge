

const style66 = {
  width: '66%'
};

function WaitingForMint() {

  return (
    <>
      <div className="card-body">
        <div className="progress" role="progressbar" aria-label="Status" aria-valuenow={66} aria-valuemin={0} aria-valuemax={100}>
          <div className="progress-bar bg-success" style={style66}>66%</div>
        </div>
        <div className="row">
          <form className='row g-3'>
            <div className="row">
              <div className="mb-3">
                <div className="d-flex float-end">
                  <strong role="status">Waiting for funds to be minted...</strong>
                  <div className="spinner-border ms-auto" aria-hidden="true"></div>
                </div>
              </div>
            </div>
          </form>
        </div>
      </div>
    </>
  );
}

export default WaitingForMint;