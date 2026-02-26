export function renderMockSupportPage(): string {
  return `<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>Mock Support Portal</title>
    <style>
      :root {
        color-scheme: light;
        --bg: #f4efe6;
        --panel: #fff8ec;
        --ink: #18241f;
        --accent: #146c4b;
        --accent-soft: #d6efe5;
        --warn: #8a4f08;
      }
      body {
        margin: 0;
        font-family: "Avenir Next", "Segoe UI", sans-serif;
        background: radial-gradient(circle at top right, #f0d9a8 0%, var(--bg) 42%);
        color: var(--ink);
      }
      .layout {
        max-width: 920px;
        margin: 30px auto;
        padding: 16px;
      }
      .panel {
        background: var(--panel);
        border: 2px solid #d9cdb4;
        border-radius: 16px;
        padding: 18px;
        box-shadow: 0 10px 20px rgba(24, 36, 31, 0.08);
      }
      h1 {
        margin-top: 0;
        font-size: 24px;
      }
      .row {
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: 12px;
      }
      label {
        display: block;
        font-size: 12px;
        margin-bottom: 4px;
        text-transform: uppercase;
        letter-spacing: 0.08em;
        font-weight: 700;
      }
      input,
      select,
      textarea,
      button {
        font: inherit;
      }
      input,
      select,
      textarea {
        width: 100%;
        border: 1px solid #bcae94;
        border-radius: 10px;
        background: #fff;
        padding: 9px 10px;
      }
      textarea {
        min-height: 90px;
      }
      button {
        background: var(--accent);
        color: white;
        border: none;
        border-radius: 10px;
        padding: 9px 14px;
        cursor: pointer;
        font-weight: 600;
      }
      button.alt {
        background: #ece1ce;
        color: var(--ink);
      }
      .chat {
        margin-top: 15px;
        border: 1px solid #cfbea1;
        border-radius: 10px;
        padding: 12px;
        background: #fff;
        min-height: 200px;
        max-height: 320px;
        overflow: auto;
      }
      .msg {
        margin-bottom: 8px;
      }
      .author {
        display: inline-block;
        padding: 3px 7px;
        border-radius: 6px;
        margin-right: 6px;
        background: var(--accent-soft);
        font-size: 12px;
        font-weight: 700;
      }
      .tray {
        margin-top: 12px;
        display: flex;
        gap: 8px;
        flex-wrap: wrap;
      }
      .status {
        margin-top: 12px;
        padding: 8px 10px;
        border-radius: 8px;
        background: #faefd9;
        color: var(--warn);
        font-weight: 600;
      }
      @media (max-width: 700px) {
        .row {
          grid-template-columns: 1fr;
        }
      }
    </style>
  </head>
  <body>
    <main class="layout">
      <section class="panel">
        <h1>Mock Support Portal</h1>
        <p>Demo portal for running automated customer support sessions.</p>

        <div class="row">
          <div>
            <label for="customerName">Customer Name</label>
            <input id="customerName" placeholder="Name" />
          </div>
          <div>
            <label for="orderId">Order ID</label>
            <input id="orderId" placeholder="ORD-123" />
          </div>
        </div>

        <div class="row" style="margin-top: 10px;">
          <div>
            <label for="issueCategory">Issue Category</label>
            <select id="issueCategory">
              <option value="unknown">Unknown</option>
              <option value="billing">Billing</option>
              <option value="shipping">Shipping</option>
              <option value="damaged">Damaged Item</option>
              <option value="account">Account</option>
            </select>
          </div>
          <div>
            <label for="uploadInput">Attachment</label>
            <input id="uploadInput" type="file" />
            <small id="uploadMeta"></small>
          </div>
        </div>

        <label for="chatInput" style="margin-top: 12px;">Chat Message</label>
        <textarea id="chatInput" placeholder="Describe the issue"></textarea>
        <button id="sendBtn" type="button">Send To Support</button>

        <section class="chat" id="chatLog"></section>
        <div class="tray" id="optionTray"></div>
        <div id="resolutionStatus" class="status">Status: waiting for message</div>
      </section>
    </main>

    <script>
      const chatLog = document.getElementById('chatLog');
      const optionTray = document.getElementById('optionTray');
      const resolutionStatus = document.getElementById('resolutionStatus');
      const uploadInput = document.getElementById('uploadInput');
      const uploadMeta = document.getElementById('uploadMeta');

      uploadInput.addEventListener('change', () => {
        const file = uploadInput.files && uploadInput.files[0];
        uploadMeta.textContent = file ? 'Attached: ' + file.name : '';
      });

      function addMessage(author, text) {
        const item = document.createElement('div');
        item.className = 'msg';
        item.innerHTML = '<span class="author">' + author + '</span><span>' + text + '</span>';
        chatLog.appendChild(item);
        chatLog.scrollTop = chatLog.scrollHeight;
      }

      function showOptions() {
        optionTray.innerHTML = [
          '<button data-option="refund" class="alt">Request Refund</button>',
          '<button data-option="human" class="alt">Escalate To Human</button>',
          '<button data-option="close" class="alt">Close Ticket</button>'
        ].join('');
      }

      document.getElementById('sendBtn').addEventListener('click', () => {
        const text = document.getElementById('chatInput').value.trim();
        if (!text) {
          return;
        }

        addMessage('User', text);
        resolutionStatus.textContent = 'Status: support bot reviewing issue';
        document.getElementById('chatInput').value = '';

        setTimeout(() => {
          addMessage('SupportBot', 'I can help with that. Select the next best action.');
          showOptions();
          resolutionStatus.textContent = 'Status: waiting for selected action';
        }, 450);
      });

      optionTray.addEventListener('click', (event) => {
        const target = event.target;
        if (!target || !target.dataset || !target.dataset.option) {
          return;
        }

        const option = target.dataset.option;
        optionTray.innerHTML = '';

        if (option === 'refund') {
          addMessage('SupportBot', 'Refund approved and will post in 3-5 business days.');
          resolutionStatus.textContent = 'Status: resolved with refund';
          return;
        }

        if (option === 'human') {
          addMessage('SupportBot', 'A human agent has taken over this case.');
          resolutionStatus.textContent = 'Status: escalated to human agent';
          return;
        }

        addMessage('SupportBot', 'Ticket closed as requested.');
        resolutionStatus.textContent = 'Status: closed';
      });
    </script>
  </body>
</html>`;
}
