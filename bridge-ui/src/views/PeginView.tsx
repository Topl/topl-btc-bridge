import { useOutletContext } from "react-router-dom";
import { PeginUIState } from "../controllers/PeginController";
import StartSession, { SessionInformation } from "./StartSession";
import WaitingForBTC from "./WaitingForBTC";
import WaitingForMint from "./WaitingForMint";
import { SessionCtx } from "../Frame";

const currentView = (session: SessionInformation, setSession: React.Dispatch<React.SetStateAction<SessionInformation>>) => {
  switch (session.currentState) {
    case PeginUIState.InitialState:
      return StartSession(session, setSession)
    case PeginUIState.SessionStarted:
      return StartSession(session, setSession)
    case PeginUIState.WaitingForBTC:
      return WaitingForBTC(session, setSession)
    case PeginUIState.WaitingForMint:
      return WaitingForMint(session)
    case PeginUIState.MintingTBTC:
      return WaitingForMint(session)
    case PeginUIState.MintedTBTC:
      return WaitingForMint(session)
    default:
      return StartSession(session, setSession)
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