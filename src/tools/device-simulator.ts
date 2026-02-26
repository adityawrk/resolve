/* eslint-disable no-console */

type PollCommandResponse = {
  command:
    | {
        id: string;
        deviceId: string;
        payload: {
          caseId: string;
          customerName: string;
          issue: string;
          orderId?: string;
          attachmentPaths: string[];
          targetPlatform: string;
          desiredOutcome: string;
        };
      }
    | null;
};

const baseUrl = process.env.BASE_URL ?? 'http://127.0.0.1:8787';
const targetPlatform = (process.env.TARGET_PLATFORM ?? 'amazon').toLowerCase();
const once = process.env.ONCE === '1';

let deviceId = process.env.DEVICE_ID;
let deviceToken = process.env.DEVICE_TOKEN;

if (!deviceId || !deviceToken) {
  const registerResponse = await fetchJson(`${baseUrl}/api/devices/register`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      name: process.env.DEVICE_NAME ?? 'Pixel Companion Simulator',
      platform: process.env.DEVICE_PLATFORM ?? 'android',
    }),
  });

  deviceId = registerResponse.device.id;
  deviceToken = registerResponse.authToken;

  console.log('Registered simulator device');
  console.log(`DEVICE_ID=${deviceId}`);
  console.log(`DEVICE_TOKEN=${deviceToken}`);
}

if (!deviceId || !deviceToken) {
  throw new Error('Device registration failed: missing deviceId or deviceToken');
}

await fetchJson(`${baseUrl}/api/devices/${deviceId}/permissions`, {
  method: 'POST',
  headers: { 'content-type': 'application/json' },
  body: JSON.stringify({
    appGrants: [
      {
        appId: targetPlatform,
        canLaunch: true,
        canNavigateSupport: true,
        canUploadMedia: true,
      },
    ],
  }),
});

console.log(`Companion simulator online for '${targetPlatform}'. Polling ${baseUrl} ...`);

while (true) {
  const poll = (await fetchJson(`${baseUrl}/api/device-agent/poll`, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'x-device-token': deviceToken,
    },
    body: JSON.stringify({ deviceId }),
  })) as PollCommandResponse;

  if (!poll.command) {
    if (once) {
      console.log('No command found; exiting because ONCE=1');
      process.exit(0);
    }

    await sleep(1000);
    continue;
  }

  console.log(`Picked command ${poll.command.id} for case ${poll.command.payload.caseId}`);

  await postCommandEvent(deviceId, deviceToken, poll.command.id, 'Launching target app', 'step');
  await sleep(500);
  await postCommandEvent(deviceId, deviceToken, poll.command.id, 'Navigating to complaint flow', 'step');
  await sleep(500);

  if (poll.command.payload.attachmentPaths.length > 0) {
    await postCommandEvent(
      deviceId,
      deviceToken,
      poll.command.id,
      `Uploading ${poll.command.payload.attachmentPaths.length} attachment(s)`,
      'step',
    );
    await sleep(500);
  }

  await postCommandEvent(deviceId, deviceToken, poll.command.id, 'Submitting complaint conversation', 'step');

  const summary = `Complaint raised on ${poll.command.payload.targetPlatform}; requested ${poll.command.payload.desiredOutcome}.`;

  await fetchJson(`${baseUrl}/api/device-agent/commands/${poll.command.id}/complete`, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'x-device-token': deviceToken,
    },
    body: JSON.stringify({
      deviceId,
      resultSummary: summary,
    }),
  });

  console.log(`Completed command ${poll.command.id}`);

  if (once) {
    process.exit(0);
  }

  await sleep(500);
}

async function postCommandEvent(
  currentDeviceId: string,
  currentDeviceToken: string,
  commandId: string,
  message: string,
  stage: 'device_ack' | 'step',
): Promise<void> {
  await fetchJson(`${baseUrl}/api/device-agent/commands/${commandId}/events`, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'x-device-token': currentDeviceToken,
    },
    body: JSON.stringify({
      deviceId: currentDeviceId,
      message,
      stage,
    }),
  });
}

async function fetchJson(url: string, init: RequestInit): Promise<any> {
  const response = await fetch(url, init);
  if (!response.ok) {
    const text = await response.text();
    throw new Error(`HTTP ${response.status} ${response.statusText}: ${text}`);
  }
  return response.json();
}

async function sleep(ms: number): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, ms));
}
