
import { getCookie, setCookie } from '../cookie-typescript-utils';
import { SessionInformation, StartSessionResponse } from '../views/StartSession';

export enum PeginUIState {
  InitialState,
  SessionStarted,
  WaitingForBTC,
  MintingTBTC,
  WaitingForMint,
  MintedTBTC
}

function stringToPeginUIState(state: string): PeginUIState {
  switch (state) {
    case "InitialState":
      return PeginUIState.InitialState;
    case "SessionStarted":
      return PeginUIState.SessionStarted;
    case "WaitingForBTC":
      return PeginUIState.WaitingForBTC;
    case "MintingTBTC":
      return PeginUIState.MintingTBTC;
    case "WaitingForMint":
      return PeginUIState.WaitingForMint;
    case "MintedTBTC":
      return PeginUIState.MintedTBTC;
    default:
      return PeginUIState.InitialState;
  }
}

export function setupSession(session: SessionInformation, setSession: React.Dispatch<React.SetStateAction<SessionInformation>>) {
  if (!session.isSet) {
    const sessionId = getCookie("sessionID");
    const escrowAddress = getCookie("escrowAddress");
    const currentState = getCookie("currentState");
    const redeemAddress = getCookie("redeemAddress");



    if (sessionId !== undefined && escrowAddress !== undefined && currentState !== undefined && redeemAddress !== undefined) {
      console.log("Session exists in cookie")
      setSession({ isSet: true, sessionID: sessionId, escrowAddress: escrowAddress, currentState: stringToPeginUIState(currentState), redeemAddress: redeemAddress });
    } 
  }
}

export function sessionStarted(setSession: React.Dispatch<React.SetStateAction<SessionInformation>>, response: StartSessionResponse) {
  setCookie("sessionID", response.sessionID);
  setCookie("escrowAddress", response.escrowAddress);
  setCookie("currentState", "SessionStarted");
  setSession({ isSet: true, sessionID: response.sessionID, escrowAddress: response.escrowAddress, currentState: PeginUIState.SessionStarted, redeemAddress: "" });
}

export function btcSent(setSession: React.Dispatch<React.SetStateAction<SessionInformation>>, session: SessionInformation) {
  setCookie("currentState", "WaitingForBTC");
  setSession({ isSet: true, sessionID: session.sessionID, escrowAddress: session.escrowAddress, currentState: PeginUIState.WaitingForBTC, redeemAddress: "" });
}
export function btcArrived(setSession: React.Dispatch<React.SetStateAction<SessionInformation>>, session: SessionInformation) {
  setCookie("currentState", "MintingTBTC");
  setSession({ isSet: true, sessionID: session.sessionID, escrowAddress: session.escrowAddress, currentState: PeginUIState.MintingTBTC, redeemAddress: "" });
}

export function mintingBTC(setSession: React.Dispatch<React.SetStateAction<SessionInformation>>, session: SessionInformation) {
  setCookie("currentState", "MintingTBTC");
  setSession({ isSet: true, sessionID: session.sessionID, escrowAddress: session.escrowAddress, currentState: PeginUIState.MintingTBTC, redeemAddress: "" });
}

export function waitingForTBTC(setSession: React.Dispatch<React.SetStateAction<SessionInformation>>, session: SessionInformation) {
  setCookie("currentState", "WaitingForMint");
  setSession({ isSet: true, sessionID: session.sessionID, escrowAddress: session.escrowAddress, currentState: PeginUIState.WaitingForMint, redeemAddress: "" });
}

export function mintedBTC(setSession: React.Dispatch<React.SetStateAction<SessionInformation>>, session: SessionInformation, address: string) {
  setCookie("currentState", "MintedTBTC");
  setCookie("redeemAddress", address);
  setSession({ isSet: true, sessionID: session.sessionID, escrowAddress: session.escrowAddress, currentState: PeginUIState.MintedTBTC, redeemAddress: address });
}