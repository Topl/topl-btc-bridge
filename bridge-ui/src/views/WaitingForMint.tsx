

import { useEffect, useState } from "react";
import { ErrorResponse, SessionInformation } from "./StartSession"
import { deleteCookie, getCookie, setCookie } from "../cookie-typescript-utils";

interface MintingStatusRequest {
  sessionID: string;
}

interface MintingStatusResponse {
  mintingStatus: string;
  address: string;
}

async function checkMintingStatus(mintingStatusRequest: MintingStatusRequest): Promise<MintingStatusResponse | ErrorResponse> {
  const response = await fetch('/api/topl-minting-status',
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(mintingStatusRequest)
    });
  if (response.status == 200) {
    const data = await response.json();
    return data;
  } else {
    return { error: "Error" };
  }
}


function WaitingForMint() {
  const [session, setSession] = useState<SessionInformation>({ isSet: false, sessionID: "", escrowAddress: "", currentState: "SessionStart", redeemAddress: "" })
  const [mintingStatus, setMintingStatus] = useState<MintingStatusResponse>({ mintingStatus: "", address: "" });

  const updateStatus = async (sessionId: string) => {
    const currentStatus = await checkMintingStatus({ sessionID: sessionId });
    console.log(currentStatus);
    if (typeof currentStatus === 'object' && ("mintingStatus" in currentStatus)) {
      setMintingStatus(currentStatus);
      console.log(currentStatus.mintingStatus);
      if (currentStatus.mintingStatus !== "MintingBTCStateMinted" ) {
        setTimeout(() => {
          updateStatus(sessionId);
        }, 5000);
      } else {
        setCookie("currentState", "MintedTBTC");
        setCookie("redeemAddress", currentStatus.address);
      }
    } else {
      deleteCookie("sessionID");
      deleteCookie("escrowAddress");
    }
  }

  useEffect(() => {
    const sessionId = getCookie("sessionID");
    const escrowAddress = getCookie("escrowAddress");
    const currentState = getCookie("currentState");
    const redeemAddress = getCookie("redeemAddress");
    if (sessionId !== undefined && escrowAddress !== undefined && currentState !== undefined && redeemAddress !== undefined) {
      setSession({ isSet: true, sessionID: sessionId, escrowAddress: escrowAddress, currentState: currentState, redeemAddress: redeemAddress });
      if (currentState === "SessionStart") {
        // redirect to waiting for BTC
        window.location.href = "/pegin/startSession";
      }
      updateStatus(sessionId);
    } else {
      window.location.href = "/pegin/startSession";
    }
  }, []);





  const style66 = {
    width: '66%'
  };
  const style75 = {
    width: '75%'
  };

  const waitingView = (mintingStatus: MintingStatusResponse) => {
    if (mintingStatus.mintingStatus === "MintingBTCStateMinting") {
      return (
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
      );
    } else if (mintingStatus.mintingStatus === "MintingBTCStateWaiting") {
      return (
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
      );
    } else {
      return (
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
                  </div>
                </div>
              </div>
            </form>
          </div>
        </div>
      );
    }
  }


  return (
    <>
      <h1 className="mt-4">Peg-in</h1>
      <div className='row g-3'>
        <div className="col"></div>
        <div className="col">
          <div className="card">
            <div className="card-header">
              Peg-in
            </div>
            {waitingView(mintingStatus)}

          </div></div>
        <div className="col"></div>
      </div>

    </>
  );
}

export default WaitingForMint;