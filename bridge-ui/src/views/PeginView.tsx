import { useOutletContext } from "react-router-dom";
import { PeginUIState } from "../controllers/PeginController";
import StartSession, { SessionInformation } from "./StartSession";
import WaitingForMint from "./WaitingForMint";
import { SessionCtx } from "../Frame";
import WaitingForRedemption from "./WaitingForRedemption";
import WaitingForClaim from "./WaitingForClaim";
import BTCWasNotSent from "./BTCWasNotSent";
import MintDidNotHappen from "./MintDidNotHappen";

function currentView(session: SessionInformation, setSession: React.Dispatch<React.SetStateAction<SessionInformation>>): JSX.Element {
  switch (session.currentState) {
    case PeginUIState.InitialState:
      return StartSession(session, setSession)
    case PeginUIState.SessionStarted:
      return StartSession(session, setSession)
    case PeginUIState.MintingTBTC:
      return WaitingForMint()
    case PeginUIState.TimeOutBTCNotSent:
      return BTCWasNotSent()
    case PeginUIState.TimeOutMintingTBTC:
      return MintDidNotHappen()
    case PeginUIState.WaitingForRedemption:
      return WaitingForRedemption(session)
    case PeginUIState.WaitingForClaim:
      return WaitingForClaim()
  }
}


function PeginView() {

  const sessionCtx = useOutletContext<SessionCtx>();

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
            {currentView(sessionCtx.session, sessionCtx.setSession)}
          </div></div>
        <div className="col"></div>
      </div>
    </>)

}

export default PeginView;