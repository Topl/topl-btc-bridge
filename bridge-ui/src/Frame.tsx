import { useEffect, useState } from 'react';
import { Link, Outlet } from 'react-router-dom';
import { PeginUIState, mintedBTC, setupSession } from './controllers/PeginController';
import { deleteCookie } from './cookie-typescript-utils';
import { ErrorResponse, SessionInformation } from './views/StartSession';

export type SessionCtx = {
  session: SessionInformation;
  setSession: React.Dispatch<React.SetStateAction<SessionInformation>>;
}


interface MintingStatusRequest {
  sessionID: string;
}

interface MintingStatusResponse {
  mintingStatus: string;
  address: string;
  bridgePKey: string;
  redeemScript: string;
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


function Frame() {


  const [session, setSession] = useState<SessionInformation>({ isSet: false, sessionID: "", escrowAddress: "", currentState: PeginUIState.InitialState, redeemAddress: "", toplBridgePKey: "", redeemTemplate: "" });
  useEffect(() => setupSession(session, setSession), []);
  const updateStatus = async (sessionId: string) => {
    if ((session.currentState === PeginUIState.MintingTBTC) ||
      (session.currentState === PeginUIState.WaitingForMint)) {
      const currentStatus = await checkMintingStatus({ sessionID: sessionId });
      if (typeof currentStatus === 'object' && ("mintingStatus" in currentStatus)) {
        console.log(currentStatus.mintingStatus);
        if (currentStatus.mintingStatus !== "MintingBTCStateMinted") {
          // if (currentStatus.mintingStatus === "MintingBTCStateMinting") {
          //   mintingBTC(setSession, session);
          // }
          // if (currentStatus.mintingStatus === "MintingBTCStateWaiting") {
          //   waitingForTBTC(setSession, session);
          // }
          setTimeout(() => {
            updateStatus(sessionId);
          }, 5000);
        } else {
          mintedBTC(setSession, session, currentStatus.address, currentStatus.bridgePKey, currentStatus.redeemScript);
        }
      }
    } else {
      setTimeout(() => {
        updateStatus(sessionId);
      }, 5000);
    }
  }

  useEffect(() => {
    updateStatus(session.sessionID);
  }, [session.currentState]);


  function handleLogout() {
    deleteCookie("sessionID");
    deleteCookie("escrowAddress");
    // reload the page
    window.location.reload();
  }


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
            <li className='nav-item'>
              <a className="nav-link" aria-current="page" href="/demo-btc-wallet">Demo BTC Wallet</a>
            </li>
            <li className='nav-item'>
              <a className="nav-link" aria-current="page" href="/demo-btc-wallet">Topl Wallet</a>
            </li>
          </ul>
          <ul className="navbar-nav ms-auto mt-2 mt-lg-0">
            <li className="nav-item"><a className="nav-link" href="#!" onClick={handleLogout}>Logout</a></li>
          </ul>
        </nav>
        <div className='container'>
          <Outlet context={{ session, setSession } satisfies SessionCtx} />
        </div>
      </div>
    </>
  )

}

export default Frame;