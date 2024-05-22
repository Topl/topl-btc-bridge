
import { getCookie, setCookie } from '../cookie-typescript-utils';
import { SessionInformation, StartSessionResponse } from '../views/StartSession';

export enum PeginUIState {
  InitialState,
  SessionStarted,
  TimeOutBTCNotSent,
  MintingTBTC,
  TimeOutMintingTBTC,
  WaitingForRedemption,
  WaitingForClaim
}

function stringToPeginUIState(state: string): PeginUIState {
  switch (state) {
    case "InitialState":
      return PeginUIState.InitialState;
    case "PeginSessionStateWaitingForBTC":
      return PeginUIState.SessionStarted;
    case "PeginSessionStateMintingTBTC": // MintingTBTC
      return PeginUIState.MintingTBTC;
    case "PeginSessionWaitingForRedemption":
      return PeginUIState.WaitingForRedemption;
    case "PeginSessionWaitingForClaim":
      return PeginUIState.WaitingForClaim;
    case "PeginSessionTimeOutBTCNotSent":
      return PeginUIState.TimeOutBTCNotSent;
    case "PeginSessionTimeOutMintingTBTC":
      return PeginUIState.TimeOutMintingTBTC;
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
    const toplBridgePKey = getCookie("toplBridgePKey");
    const redeemTemplate = getCookie("redeemTemplate");

    if (sessionId !== undefined && escrowAddress !== undefined && currentState !== undefined && redeemAddress !== undefined && toplBridgePKey !== undefined && redeemTemplate !== undefined) {
      console.log("Session exists in cookie")
      setSession({ isSet: true, sessionID: sessionId, escrowAddress: escrowAddress, currentState: stringToPeginUIState(currentState), redeemAddress: redeemAddress, toplBridgePKey: toplBridgePKey, redeemTemplate: redeemTemplate });
    }
  }
}

export function sessionStarted(setSession: React.Dispatch<React.SetStateAction<SessionInformation>>, response: StartSessionResponse) {
  setCookie("sessionID", response.sessionID);
  setCookie("escrowAddress", response.escrowAddress);
  setCookie("currentState", "PeginSessionStateWaitingForBTC");
  setSession({ isSet: true, sessionID: response.sessionID, escrowAddress: response.escrowAddress, currentState: PeginUIState.SessionStarted, redeemAddress: "", toplBridgePKey: "", redeemTemplate: "" });
}

export function btcArrived(setSession: React.Dispatch<React.SetStateAction<SessionInformation>>, session: SessionInformation) {
  setCookie("currentState", "PeginSessionStateMintingTBTC");
  setSession({ isSet: true, sessionID: session.sessionID, escrowAddress: session.escrowAddress, currentState: PeginUIState.MintingTBTC, redeemAddress: "", toplBridgePKey: "", redeemTemplate: "" });
}

export function mintingBTC(setSession: React.Dispatch<React.SetStateAction<SessionInformation>>, session: SessionInformation) {
  setCookie("currentState", "PeginSessionStateMintingTBTC");
  setSession({ isSet: true, sessionID: session.sessionID, escrowAddress: session.escrowAddress, currentState: PeginUIState.MintingTBTC, redeemAddress: "", toplBridgePKey: "", redeemTemplate: "" });
}

export function timeOutMintingBTC(setSession: React.Dispatch<React.SetStateAction<SessionInformation>>, session: SessionInformation) {
  setCookie("currentState", "PeginSessionTimeOutMintingTBTC");
  setSession({ isSet: true, sessionID: session.sessionID, escrowAddress: session.escrowAddress, currentState: PeginUIState.TimeOutMintingTBTC, redeemAddress: "", toplBridgePKey: "", redeemTemplate: "" });
}

export function timeOutBTCNotSent(setSession: React.Dispatch<React.SetStateAction<SessionInformation>>, session: SessionInformation) {
  setCookie("currentState", "PeginSessionTimeOutBTCNotSent");
  setSession({ isSet: true, sessionID: session.sessionID, escrowAddress: session.escrowAddress, currentState: PeginUIState.TimeOutBTCNotSent, redeemAddress: "", toplBridgePKey: "", redeemTemplate: "" });
}


export function mintedBTC(setSession: React.Dispatch<React.SetStateAction<SessionInformation>>, session: SessionInformation, address: string, toplBridgePKey: string, redeemTemplate: string) {
  setCookie("currentState", "PeginSessionWaitingForRedemption");
  setCookie("redeemAddress", address);
  setCookie("toplBridgePKey", toplBridgePKey);
  setCookie("redeemTemplate", redeemTemplate);
  setSession({ isSet: true, sessionID: session.sessionID, escrowAddress: session.escrowAddress, currentState: PeginUIState.WaitingForRedemption, redeemAddress: address, toplBridgePKey: toplBridgePKey, redeemTemplate: redeemTemplate });
}

export function claimedTBTC(setSession: React.Dispatch<React.SetStateAction<SessionInformation>>, session: SessionInformation) {
  setCookie("currentState", "PeginSessionWaitingForClaim");
  setSession({ isSet: true, sessionID: session.sessionID, escrowAddress: session.escrowAddress, currentState: PeginUIState.WaitingForClaim, redeemAddress: session.redeemAddress, toplBridgePKey: session.toplBridgePKey, redeemTemplate: session.redeemTemplate });
}