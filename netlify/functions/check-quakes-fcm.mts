import { getStore } from "@netlify/blobs";
import { createSign } from "node:crypto";
import type { Config } from "@netlify/functions";

type BmkgResponse = {
  Infogempa?: {
    gempa?: {
      Tanggal?: string;
      Jam?: string;
      DateTime?: string;
      Magnitude?: string;
      Kedalaman?: string;
      Wilayah?: string;
      Potensi?: string;
      Dirasakan?: string;
    };
  };
};

function base64url(value: string | Buffer): string {
  return Buffer.from(value).toString("base64")
    .replace(/=/g, "")
    .replace(/\+/g, "-")
    .replace(/\//g, "_");
}

async function getAccessToken(serviceAccount: {
  client_email: string;
  private_key: string;
  token_uri?: string;
}): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const header = base64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = base64url(JSON.stringify({
    iss: serviceAccount.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: serviceAccount.token_uri || "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600
  }));
  const unsigned = header + "." + claims;
  const signer = createSign("RSA-SHA256");
  signer.update(unsigned);
  signer.end();
  const assertion = unsigned + "." + base64url(signer.sign(serviceAccount.private_key));

  const response = await fetch(serviceAccount.token_uri || "https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion
    })
  });
  const data = await response.json() as { access_token?: string; error?: string; error_description?: string };
  if (!response.ok || !data.access_token) {
    throw new Error("Google OAuth token failed: " + (data.error_description || data.error || response.status));
  }
  return data.access_token;
}

export default async () => {
  const rawServiceAccount = Netlify.env.get("FCM_SERVICE_ACCOUNT_JSON");
  if (!rawServiceAccount) {
    console.error("Missing FCM_SERVICE_ACCOUNT_JSON environment variable");
    return new Response("FCM service account is not configured", { status: 500 });
  }

  try {
    const serviceAccount = JSON.parse(rawServiceAccount) as {
      project_id: string;
      client_email: string;
      private_key: string;
      token_uri?: string;
    };
    if (!serviceAccount.project_id || !serviceAccount.client_email || !serviceAccount.private_key) {
      throw new Error("Service account JSON is missing required fields");
    }

    const bmkgResponse = await fetch("https://data.bmkg.go.id/DataMKG/TEWS/autogempa.json", {
      headers: { "accept": "application/json" },
      signal: AbortSignal.timeout(10000)
    });
    if (!bmkgResponse.ok) throw new Error("BMKG endpoint returned HTTP " + bmkgResponse.status);

    const payload = await bmkgResponse.json() as BmkgResponse;
    const quake = payload?.Infogempa?.gempa;
    if (!quake) throw new Error("BMKG response did not contain Infogempa.gempa");

    const dateLabel = quake.DateTime || [quake.Tanggal, quake.Jam].filter(Boolean).join(" ");
    if (!dateLabel) throw new Error("BMKG response did not contain an earthquake timestamp");

    // Ignore old records: do not send a stale notification immediately after deployment.
    const parsedDate = quake.DateTime ? Date.parse(quake.DateTime) : NaN;
    const key = dateLabel + "|" + (quake.Magnitude || "") + "|" + (quake.Wilayah || "");
    if (Number.isFinite(parsedDate) && Date.now() - parsedDate > 15 * 60 * 1000) {
      return Response.json({ ok: true, sent: false, reason: "latest BMKG record is older than 15 minutes" });
    }

    const store = getStore("wrs-gempa-fcm-state");
    const lastKey = await store.get("last-sent-quake");
    if (lastKey === key) {
      return Response.json({ ok: true, sent: false, reason: "already notified" });
    }

    const accessToken = await getAccessToken(serviceAccount);
    const magnitude = quake.Magnitude ? "M " + quake.Magnitude : "Gempa bumi";
    const title = /tsunami/i.test(quake.Potensi || "") ? "PERINGATAN POTENSI TSUNAMI" : "Info Gempa BMKG";
    const body = [
      magnitude,
      quake.Wilayah || "Lokasi tidak tersedia",
      quake.Kedalaman ? "Kedalaman " + quake.Kedalaman : "",
      quake.Potensi || ""
    ].filter(Boolean).join(" • ");

    const fcmResponse = await fetch(
      "https://fcm.googleapis.com/v1/projects/" + encodeURIComponent(serviceAccount.project_id) + "/messages:send",
      {
        method: "POST",
        headers: {
          "authorization": "Bearer " + accessToken,
          "content-type": "application/json"
        },
        body: JSON.stringify({
          message: {
            topic: "wrs-gempa-alerts",
            notification: { title, body },
            data: {
              type: "earthquake",
              timestamp: dateLabel,
              magnitude: quake.Magnitude || "",
              location: quake.Wilayah || "",
              potential: quake.Potensi || ""
            },
            android: { priority: "high", notification: { channel_id: "wrs_gempa_alerts" } }
          }
        })
      }
    );

    const fcmResult = await fcmResponse.json() as { name?: string; error?: { message?: string; status?: string } };
    if (!fcmResponse.ok) {
      throw new Error("FCM send failed (" + fcmResponse.status + "): " + (fcmResult.error?.message || fcmResult.error?.status || "unknown error"));
    }

    await store.set("last-sent-quake", key);
    console.log("FCM notification sent for BMKG record:", dateLabel);
    return Response.json({ ok: true, sent: true, timestamp: dateLabel, messageId: fcmResult.name });
  } catch (error) {
    const message = error instanceof Error ? error.message : "Unknown error";
    console.error("FCM quake check failed:", message);
    return Response.json({ ok: false, error: message }, { status: 500 });
  }
};

export const config: Config = {
  schedule: "* * * * *"
};
