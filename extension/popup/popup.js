/**
 * Resolve - Extension Popup
 * Controls the user interface for submitting issues and monitoring the agent.
 */

const BACKEND_URL_KEY = 'resolve_backend_url';
const DEFAULT_BACKEND = 'http://127.0.0.1:8787';

// ─── DOM refs ───────────────────────────────────────────────────────────────

const $ = (sel) => document.querySelector(sel);
const connectionDot = $('#connectionDot');
const widgetBanner = $('#widgetBanner');
const widgetStatus = $('#widgetStatus');

// Sections
const formSection = $('#formSection');
const activeSection = $('#activeSection');
const pausedSection = $('#pausedSection');
const completedSection = $('#completedSection');
const errorSection = $('#errorSection');

// Form controls
const issueInput = $('#issueInput');
const desiredOutcome = $('#desiredOutcome');
const fileInput = $('#fileInput');
const fileList = $('#fileList');
const startBtn = $('#startBtn');

// Active controls
const agentStatus = $('#agentStatus');
const activityFeed = $('#activityFeed');
const pauseBtn = $('#pauseBtn');
const stopBtn = $('#stopBtn');

// Paused controls
const pauseReason = $('#pauseReason');
const pausePrompt = $('#pausePrompt');
const humanResponseField = $('#humanResponseField');
const humanResponse = $('#humanResponse');
const approveBtn = $('#approveBtn');
const stopPausedBtn = $('#stopPausedBtn');

// Completed
const resolutionSummary = $('#resolutionSummary');
const completedFeed = $('#completedFeed');
const newCaseBtn = $('#newCaseBtn');

// Error
const errorMessage = $('#errorMessage');
const retryBtn = $('#retryBtn');

// ─── State ──────────────────────────────────────────────────────────────────

let attachedFiles = [];
let activeCaseId = null;
let widgetDetected = false;

// ─── Widget detection ───────────────────────────────────────────────────────

async function checkForWidget() {
  try {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    if (!tab?.id) return;

    const response = await chrome.runtime.sendMessage({
      type: 'get_widget_status',
      tabId: tab.id,
    });

    if (response?.widgetFound) {
      widgetDetected = true;
      widgetBanner.className = 'banner banner-found';
      widgetStatus.textContent = `${response.provider || 'Chat'} widget detected`;
      updateStartButton();
    } else {
      widgetBanner.className = 'banner banner-none';
      widgetStatus.textContent = 'No chat widget found on this page';
    }
  } catch {
    widgetBanner.className = 'banner banner-none';
    widgetStatus.textContent = 'Cannot access this page';
  }
}

// ─── Connection status ──────────────────────────────────────────────────────

async function checkConnection() {
  try {
    const response = await chrome.runtime.sendMessage({ type: 'get_connection_status' });
    if (response?.connected) {
      connectionDot.className = 'dot dot-connected';
      connectionDot.title = 'Connected to server';
    } else {
      connectionDot.className = 'dot dot-disconnected';
      connectionDot.title = 'Disconnected from server';
    }
  } catch {
    connectionDot.className = 'dot dot-disconnected';
    connectionDot.title = 'Disconnected';
  }
}

// ─── File handling ──────────────────────────────────────────────────────────

fileInput.addEventListener('change', () => {
  const newFiles = Array.from(fileInput.files);
  for (const file of newFiles) {
    if (!attachedFiles.some((f) => f.name === file.name && f.size === file.size)) {
      attachedFiles.push(file);
    }
  }
  renderFileList();
  fileInput.value = '';
});

function renderFileList() {
  fileList.innerHTML = '';
  for (let i = 0; i < attachedFiles.length; i++) {
    const tag = document.createElement('span');
    tag.className = 'file-tag';
    tag.innerHTML = `${truncate(attachedFiles[i].name, 20)}<button data-idx="${i}">&times;</button>`;
    fileList.appendChild(tag);
  }

  fileList.querySelectorAll('button').forEach((btn) => {
    btn.addEventListener('click', () => {
      attachedFiles.splice(Number(btn.dataset.idx), 1);
      renderFileList();
    });
  });
}

function truncate(str, max) {
  return str.length > max ? str.slice(0, max - 3) + '...' : str;
}

// ─── Form validation ────────────────────────────────────────────────────────

issueInput.addEventListener('input', updateStartButton);

function updateStartButton() {
  const hasIssue = issueInput.value.trim().length >= 5;
  startBtn.disabled = !hasIssue || !widgetDetected;
  if (!widgetDetected && hasIssue) {
    startBtn.textContent = 'No widget detected';
  } else {
    startBtn.textContent = 'Resolve It';
  }
}

// ─── Start case ─────────────────────────────────────────────────────────────

startBtn.addEventListener('click', async () => {
  if (startBtn.disabled) return;

  const issue = issueInput.value.trim();
  const outcome = desiredOutcome.value;

  // Convert files to base64 for transfer
  const fileData = await Promise.all(
    attachedFiles.map(async (file) => ({
      name: file.name,
      type: file.type,
      data: await fileToBase64(file),
    }))
  );

  try {
    const response = await chrome.runtime.sendMessage({
      type: 'start_case',
      payload: { issue, desiredOutcome: outcome, files: fileData },
    });

    if (response?.caseId) {
      activeCaseId = response.caseId;
      showSection('active');
      addFeedItem('system', 'Case created. Agent starting...');
    } else {
      showError(response?.error || 'Failed to create case');
    }
  } catch (err) {
    showError(err.message || 'Failed to connect to server');
  }
});

function fileToBase64(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result.split(',')[1]);
    reader.onerror = reject;
    reader.readAsDataURL(file);
  });
}

// ─── Agent controls ─────────────────────────────────────────────────────────

pauseBtn.addEventListener('click', () => {
  chrome.runtime.sendMessage({ type: 'pause_case', caseId: activeCaseId });
  agentStatus.textContent = 'Pausing...';
});

stopBtn.addEventListener('click', () => {
  chrome.runtime.sendMessage({ type: 'stop_case', caseId: activeCaseId });
  showSection('form');
  resetForm();
});

approveBtn.addEventListener('click', () => {
  const userInput = humanResponse.value.trim();
  chrome.runtime.sendMessage({
    type: 'approve_case',
    caseId: activeCaseId,
    userInput: userInput || undefined,
  });
  showSection('active');
  addFeedItem('system', 'Resumed by user');
});

stopPausedBtn.addEventListener('click', () => {
  chrome.runtime.sendMessage({ type: 'stop_case', caseId: activeCaseId });
  showSection('form');
  resetForm();
});

newCaseBtn.addEventListener('click', () => {
  showSection('form');
  resetForm();
});

retryBtn.addEventListener('click', () => {
  showSection('form');
});

// ─── Message listener (from background) ─────────────────────────────────────

chrome.runtime.onMessage.addListener((msg) => {
  if (msg.type === 'agent_event' && msg.caseId === activeCaseId) {
    handleAgentEvent(msg.event);
  }
  if (msg.type === 'widget_detected') {
    widgetDetected = true;
    widgetBanner.className = 'banner banner-found';
    widgetStatus.textContent = `${msg.provider || 'Chat'} widget detected`;
    updateStartButton();
  }
  if (msg.type === 'connection_status') {
    connectionDot.className = msg.connected ? 'dot dot-connected' : 'dot dot-disconnected';
  }
});

function handleAgentEvent(event) {
  switch (event.type) {
    case 'action':
      addFeedItem('action', event.message);
      agentStatus.textContent = event.message;
      break;
    case 'agent_message':
      addFeedItem('agent', `Agent: "${event.text}"`);
      break;
    case 'support_message':
      addFeedItem('support', `Support: "${event.text}"`);
      break;
    case 'waiting':
      agentStatus.textContent = 'Waiting for support response...';
      addFeedItem('system', 'Waiting for response...');
      break;
    case 'paused':
      showSection('paused');
      pauseReason.textContent = event.reason || 'The agent needs your input';
      if (event.needsInput) {
        humanResponseField.style.display = 'block';
        humanResponse.value = '';
        humanResponse.placeholder = event.inputPrompt || 'Your response...';
      } else {
        humanResponseField.style.display = 'none';
      }
      break;
    case 'completed':
      showSection('completed');
      resolutionSummary.textContent = event.summary || 'Issue resolved.';
      // Copy activity feed to completed section
      completedFeed.innerHTML = activityFeed.innerHTML;
      break;
    case 'error':
      showError(event.message || 'An unexpected error occurred');
      break;
    default:
      addFeedItem('system', event.message || JSON.stringify(event));
  }
}

// ─── UI helpers ─────────────────────────────────────────────────────────────

function showSection(name) {
  formSection.classList.toggle('hidden', name !== 'form');
  activeSection.classList.toggle('hidden', name !== 'active');
  pausedSection.classList.toggle('hidden', name !== 'paused');
  completedSection.classList.toggle('hidden', name !== 'completed');
  errorSection.classList.toggle('hidden', name !== 'error');
}

function showError(msg) {
  errorMessage.textContent = msg;
  showSection('error');
}

function addFeedItem(badge, text) {
  const item = document.createElement('div');
  item.className = 'feed-item';
  item.innerHTML = `<span class="feed-badge feed-badge-${badge}">${badge}</span><span class="feed-text">${escapeHtml(text)}</span>`;
  activityFeed.appendChild(item);
  activityFeed.scrollTop = activityFeed.scrollHeight;
}

function escapeHtml(str) {
  const d = document.createElement('div');
  d.textContent = str;
  return d.innerHTML;
}

function resetForm() {
  activeCaseId = null;
  issueInput.value = '';
  attachedFiles = [];
  fileList.innerHTML = '';
  activityFeed.innerHTML = '';
  updateStartButton();
}

// ─── Init ───────────────────────────────────────────────────────────────────

checkConnection();
checkForWidget();
setInterval(checkConnection, 5000);
