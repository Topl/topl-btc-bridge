

import { PeginUIState } from "../controllers/PeginController";
import { SessionInformation } from "./StartSession";


function WaitingForMint(session: SessionInformation) {


  const style66 = {
    width: '66%'
  };
  const style75 = {
    width: '75%'
  };

  const waitingView = (mintingStatus: PeginUIState, redeemAddress: string, redeemTemplate: string) => {
    if (mintingStatus === PeginUIState.MintingTBTC) {
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
                    <div className="d-flex align-items-center">
                      <strong role="status">Starting tBTC minting process...</strong>
                      <div className="spinner-border ms-auto" aria-hidden="true"></div>
                    </div>
                  </div>
                </div>
              </form>
            </div>
          </div>
        </>
      );
    } else if (mintingStatus === PeginUIState.WaitingForMint) {
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
                    <div className="d-flex align-items-center">
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
    } else {
      return (
        <>
          <div className="card-body">
            <div className="progress" role="progressbar" aria-label="Status" aria-valuenow={75} aria-valuemin={0} aria-valuemax={100}>
              <div className="progress-bar bg-success" style={style75}>75%</div>
            </div>
            <div className="row">
              <form className='row g-3'>
                <div className="row">
                  <div className="mb-3">
                    <div className="d-flex align-items-center">
                      <strong role="status">Tokens minted sucessfully</strong>
                      <div className="input-group mb-3">
                        <span className="input-group-text" id="basic-addon1">Redeem address</span>
                        <input value={redeemAddress} type="text" className="form-control" aria-label="Redeem address" aria-describedby="basic-addon1" />
                      </div>
                      <div className="input-group mb-3">
                        <span className="input-group-text" id="basic-addon1">Redeem template</span>
                        <input value={redeemTemplate} type="text" className="form-control" aria-label="Redeem template" aria-describedby="basic-addon1" />
                      </div>
                    </div>
                  </div>
                </div>
              </form>
            </div>
          </div>
        </>
      );
    }
  }


  return (
    <>
      {waitingView(session.currentState, session.redeemAddress, session.redeemTemplate)}
    </>
  );
}

export default WaitingForMint;