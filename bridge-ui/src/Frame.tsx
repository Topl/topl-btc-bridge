import { useEffect, useState } from 'react';
import { Link, Outlet } from 'react-router-dom';
import { PeginUIState, mintedBTC, mintingBTC, setupSession } from './controllers/PeginController';
import { deleteCookie } from './cookie-typescript-utils';
import { SessionInformation } from './views/StartSession';

export type SessionCtx = {
  session: SessionInformation;
  setSession: React.Dispatch<React.SetStateAction<SessionInformation>>;
}

function Frame() {

  const [session, setSession] = useState<SessionInformation>({ isSet: false, sessionID: "", escrowAddress: "", currentState: PeginUIState.InitialState, redeemAddress: "", toplBridgePKey: "", redeemTemplate: "" });
  useEffect(() => setupSession(session, setSession), []);
  useEffect(() => {
    const sessionPoll = setInterval(async () => {
      if ((session.currentState == PeginUIState.SessionStarted)) {
        const response = await fetch('/api/topl-minting-status', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json'
          },
          body: JSON.stringify({ sessionID: session.sessionID })
        })
        if (response.status == 200) {
          const data = await response.json();
          const mintStatus = (data?.mintingStatus || "") as string
          if (mintStatus !== "PeginSessionStateWaitingForBTC") {
            mintingBTC(setSession, session)
          }
          console.log("mintStatus: " + mintStatus)
        } else {
          console.log(response)
        }
      } else if (session.currentState == PeginUIState.MintingTBTC) {
        const response = await fetch('/api/topl-minting-status', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json'
          },
          body: JSON.stringify({ sessionID: session.sessionID })
        })
        if (response.status == 200) {
          const data = await response.json();
          const mintStatus = (data?.mintingStatus || "") as string
          if (mintStatus === "PeginSessionWaitingForRedemption") {
            mintedBTC(setSession, session, data.address, data.bridgePKey, data.redeemScript);
            clearInterval(sessionPoll)
          }
        }
        else {
          console.log(response)
        }
      } else if (session.currentState == PeginUIState.MintedTBTC) {
        clearInterval(sessionPoll)
      }
    }, 5000)
    return () => {
      clearInterval(sessionPoll);
    }
  })


  function handleLogout() {
    deleteCookie("sessionID");
    deleteCookie("escrowAddress");
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