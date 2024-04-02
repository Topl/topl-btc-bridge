import { useEffect, useState } from 'react';
import { getCookie, setCookie } from '../cookie-typescript-utils';

export interface SessionInformation {
  isSet: boolean;
  sessionID: string;
  escrowAddress: string;
  currentState: string;
  redeemAddress: string;
}

export interface StartSessionRequest {
  pkey: string;
  sha256: string;
}

interface StartSessionResponse {
  sessionID: string;
  script: string;
  escrowAddress: string;
  descriptor: string;
}
export interface ErrorResponse {
  error: string;
}


async function startSession(startSessionRequest: StartSessionRequest): Promise<StartSessionResponse | ErrorResponse> {
  const response = await fetch('/api/start-session-pegin',
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(startSessionRequest)
    });
  const data = await response.json();
  return data;
}

function alertInstructions(isSet: boolean) {
  if (!isSet) {
    return (
      <div className="alert alert-primary" role="alert">
        Please enter the hash of the secret to start the peg-in process.
      </div>
    )
  } else {
    return (
      <div className="alert alert-success" role="alert">
        Session started, please transfer the funds to the escrow address.
      </div>
    )
  }
}

function errorValidation(error: string) {
  if (error !== "") {
    return (
      <div className="invalid-feedback">
        {error}
      </div>
    )
  } else {
    return (
      <div></div>
    )
  }
}

function StartSession() {

  const [session, setSession] = useState<SessionInformation>({ isSet: false, sessionID: "", escrowAddress: "", currentState: "SessionStart", redeemAddress: "" })
  const [hash, setHash] = useState<string>("")
  const [error, setError] = useState<string>("")

  useEffect(() => {
    const sessionId = getCookie("sessionID");
    const escrowAddress = getCookie("escrowAddress");
    const currentState = getCookie("currentState");
    const redeemAddress = getCookie("redeemAddress");
    if (sessionId !== undefined && escrowAddress !== undefined && currentState !== undefined && redeemAddress !== undefined) {
      setSession({ isSet: true, sessionID: sessionId, escrowAddress: escrowAddress, currentState: currentState, redeemAddress: redeemAddress });
      if (currentState === "WaitingForBTC") {
        // redirect to waiting for BTC
        window.location.href = "/pegin/waitingForFunds";
      }
      if (currentState === "MintingTBTC") {
        // redirect to minting
        window.location.href = "/pegin/minting";
      }
    } else {
      setSession({ isSet: false, sessionID: "", escrowAddress: "", currentState: "SessionStart", redeemAddress: "" });
    }
  }, []);

  async function handleSubmitSha(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const startSessionRequest: StartSessionRequest = {
      pkey: "0295bb5a3b80eeccb1e38ab2cbac2545e9af6c7012cdc8d53bd276754c54fc2e4a",
      sha256: hash
    }
    const response = await startSession(startSessionRequest);
    if (typeof response === 'object' && !("error" in response)) {
      setSession({ isSet: true, sessionID: response.sessionID, escrowAddress: response.escrowAddress, currentState: "SessionStarted", redeemAddress: "" });
      setCookie("sessionID", response.sessionID);
      setCookie("escrowAddress", response.escrowAddress);
      setCookie("currentState", "SessionStarted");
      setCookie("redeemAddress", "");
      setError("");
    } else {
      setError(response.error);
    }
  }

  async function handleSubmitBTCTransferred(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // redirect to waiting for BTC
    setCookie("currentState", "WaitingForBTC");
    window.location.href = "/pegin/waitingForFunds";
  }
  const style = {
    width: '25%'
  };


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
            <div className="card-body">
              <div className="progress" role="progressbar" aria-label="Status" aria-valuenow={25} aria-valuemin={0} aria-valuemax={100}>
                <div className="progress-bar bg-success" style={style}>25%</div>
              </div>
              <div className="row">
                <form className='row g-3' onSubmit={handleSubmitSha}>
                  <div className="row">
                    <div className="mb-3">
                      <label htmlFor="hash" className="form-label">SHA-256 Hash</label>
                      <input value={hash} onChange={(e) => setHash(e.target.value)} type="text" className={error === "" ? "form-control" : "form-control is-invalid"} id="hash" placeholder="497a39b618484855ebb5a2cabf6ee52ff092e7c17f8bfe79313529f9774f83a2" />
                      {errorValidation(error)}
                    </div>
                    <div className="mb-3">
                      <button type="submit" className="btn btn-primary mb-3" disabled={session.isSet}>Start Session</button>
                    </div>
                  </div>
                </form>
                <form className='row g-3' onSubmit={handleSubmitBTCTransferred}>
                  <div className="row">
                    <div className="mb-3">
                      <label htmlFor="escrowAddress" className="form-label">Escrow Address</label>
                      <input type="text" value={session.escrowAddress} className="form-control" id="escrowAddress" disabled />
                    </div>
                    <div className="mb-3">
                      <button type="submit" className="btn btn-primary mb-3" disabled={session.currentState !== 'SessionStarted'}>BTC Transferred</button>
                    </div>
                  </div>
                </form>
              </div>
              {alertInstructions(session.isSet)}
            </div>

          </div></div>
        <div className="col"></div>
      </div>

    </>
  )
}

export default StartSession