

import { btcArrived } from "../controllers/PeginController";
import { ErrorResponse, SessionInformation } from "./StartSession";

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


function WaitingForBTC(session: SessionInformation, setSession: React.Dispatch<React.SetStateAction<SessionInformation>>) {

  async function handleBTCArrived(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const confirmDepositRequest: ConfirmDepositRequest = {
      sessionID: session.sessionID,
      amount: 100000
    }

    const response = await confirmDeposit(confirmDepositRequest);
    if (typeof response === 'object' && !("error" in response)) {
      btcArrived(setSession, session);
    }
  }


  const style = {
    width: '50%'
  };


  return (
    <>
      <div className="card-body">
        <div className="progress" role="progressbar" aria-label="Status" aria-valuenow={50} aria-valuemin={0} aria-valuemax={100}>
          <div className="progress-bar bg-success" style={style}>50%</div>
        </div>
        <div className="row">
          <form className='row g-3' onSubmit={handleBTCArrived}>
            <div className="row">
              <div className="mb-3">
                <strong role="status">Waiting for funds to arrive to escrow address...</strong>
                <div className="d-flex float-end">
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
    </>
  );
}

export default WaitingForBTC;