

import { useEffect, useState } from "react";
import { ErrorResponse, SessionInformation } from "./StartSession"
import { getCookie, setCookie } from "../cookie-typescript-utils";

interface ConfirmDepositRequest {
  sessionID: string;
  amount: number;
}

interface ConfirmDepositResponse {
  txId: string
}

async function confirmDeposit(confirmDepositRequest: ConfirmDepositRequest): Promise<ConfirmDepositResponse | ErrorResponse> {
  const response = await fetch('/api/confirm-deposit-btc',
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(confirmDepositRequest)
    });
  const data = await response.json();
  return data;
}


function WaitingForBTC() {
  const [session, setSession] = useState<SessionInformation>({ isSet: false, sessionID: "", escrowAddress: "", currentState: "SessionStart" })

  useEffect(() => {
    const sessionId = getCookie("sessionID");
    const escrowAddress = getCookie("escrowAddress");
    const currentState = getCookie("currentState");
    if (sessionId !== undefined && escrowAddress !== undefined && currentState !== undefined) {
      setSession({ isSet: true, sessionID: sessionId, escrowAddress: escrowAddress, currentState: currentState });
    } else {
      setSession({ isSet: false, sessionID: "", escrowAddress: "", currentState: "SessionStart" });
    }
  }, []);



  async function handleBTCArrived(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const confirmDepositRequest: ConfirmDepositRequest = {
      sessionID: session.sessionID,
      amount: 100000
    }

    const response = await confirmDeposit(confirmDepositRequest);
    if (typeof response === 'object' && !("error" in response)) {
      setSession({ isSet: true, sessionID: session.sessionID, escrowAddress: session.escrowAddress, currentState: "MintingStarted" });
      setCookie("currentState", "MintingStarted");
    }
  }



  const style = {
    width: '50%'
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
              <div className="progress" role="progressbar" aria-label="Status" aria-valuenow={50} aria-valuemin={0} aria-valuemax={100}>
                <div className="progress-bar bg-success" style={style}>50%</div>
              </div>
              <div className="row">
                <form className='row g-3' onSubmit={handleBTCArrived}>
                  <div className="row">
                    <div className="mb-3">
                      <div className="d-flex align-items-center">
                        <strong role="status">Waiting for funds to arrive to escrow address...</strong>
                        <div className="spinner-border ms-auto" aria-hidden="true"></div>
                      </div>
                    </div>

                    <div className="mb-3">
                      <button type="submit" className="btn btn-primary mb-3">Funds arrived!</button>
                    </div>
                  </div>
                </form>
              </div>
            </div>

          </div></div>
        <div className="col"></div>
      </div>

    </>
  );
}

export default WaitingForBTC;