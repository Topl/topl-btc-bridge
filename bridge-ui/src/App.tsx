import { useEffect, useState } from 'react';
import './App.css';
import { getCookie, setCookie } from './cookie-typescript-utils';

interface SessionInformation {
  isSet: boolean;
  sessionID: string;
  escrowAddress: string;
}

interface StartSessionRequest {
  pkey: string;
  sha256: string;
}

interface StartSessionResponse {
  sessionID: string;
  script: string;
  escrowAddress: string;
  descriptor: string;
}
interface ErrorResponse {
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
    console.log("The error in valudation: "+ error);
    return (
      <div className="invalid-feedback">
       {error}
      </div>
    )
  } else {
    console.log("No error in validation.");
    return (
      <div></div>
    )
  }
}

function App() {

  const [session, setSession] = useState<SessionInformation>({ isSet: false, sessionID: "", escrowAddress: "" })
  const [hash, setHash] = useState<string>("")
  const [error, setError] = useState<string>("")

  useEffect(() => {
    const sessionId = getCookie("sessionID");
    const escrowAddress = getCookie("escrowAddress");
    if (sessionId !== undefined && escrowAddress !== undefined) {
      setSession({ isSet: true, sessionID: sessionId, escrowAddress: escrowAddress });
    } else {
      setSession({ isSet: false, sessionID: "", escrowAddress: "" });
    }
  }, []);

  async function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const startSessionRequest: StartSessionRequest = {
      pkey: "0295bb5a3b80eeccb1e38ab2cbac2545e9af6c7012cdc8d53bd276754c54fc2e4a",
      sha256: hash
    }
    const response = await startSession(startSessionRequest);
    if (typeof response === 'object' && !("error" in response)) {
      setSession({ isSet: true, sessionID: response.sessionID, escrowAddress: response.escrowAddress });
      setCookie("sessionID", response.sessionID);
      setCookie("escrowAddress", response.escrowAddress);
      setError("");
    } else {
      console.log("The error: "+ response.error);
      setError(response.error);
    }
  }


  return (
    <>
      <h1 className="mt-4">Peg-in</h1>
      <div className='row g-3'>
        <div className="col"></div>
        <div className="col">
          <div className="card">
            <form className='row g-3' onSubmit={handleSubmit}>
              <div className="card-body">
                <div className="row">
                  <div className="mb-3">
                    <label htmlFor="hash" className="form-label">SHA-256 Hash</label>
                    <input value={hash} onChange={(e) => setHash(e.target.value)} type="text" className={ error === "" ? "form-control" : "form-control is-invalid" }  id="hash" placeholder="497a39b618484855ebb5a2cabf6ee52ff092e7c17f8bfe79313529f9774f83a2" />
                    {errorValidation(error)}
                  </div>
                  <div className="mb-3">
                    <button type="submit" className="btn btn-primary mb-3" disabled={session.isSet}>Start Session</button>
                  </div>
                </div>
              </div>
            </form>
            <form className='row g-3'>
              <div className="mb-3">
                <label htmlFor="escrowAddress" className="form-label">Escrow Address</label>
                <input type="text" value={session.escrowAddress} className="form-control" id="escrowAddress" disabled />
              </div>
            </form>
            {alertInstructions(session.isSet)}
          </div></div>
        <div className="col"></div>
      </div>

    </>
  )
}

export default App
