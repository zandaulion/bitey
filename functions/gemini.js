// The vision call, as server/gemini.js makes it.
//
// Deliberately the same transport and the same prompt: Bitey AI on Android and
// photo analysis in the PWA must read a plate the same way, or two people
// photographing the same meal get two different diaries. The prompt and schema
// come from core/analysis/prompt.js; only the credential differs, because this
// copy runs where a Secret Manager secret is available and the other does not.

import { buildPrompt, RESPONSE_SCHEMA } from './core/analysis/prompt.js';

const ENDPOINT_BASE = 'https://generativelanguage.googleapis.com/v1beta/models/';

export const getModel = () => (process.env.GEMINI_MODEL || 'gemini-3.8-flash').trim();

export class AnalysisError extends Error {
  constructor(code, message, status = 502) {
    super(message);
    this.code = code;
    this.status = status;
  }
}

export async function analysePhoto(
  { apiKey, imageBase64, mimeType = 'image/jpeg', correction = null, locale = 'en' },
  fetchImpl = fetch
) {
  if (!apiKey) {
    throw new AnalysisError('not_configured', 'Photo analysis is not configured yet.', 503);
  }

  const body = {
    contents: [{
      parts: [
        { inline_data: { mime_type: mimeType, data: imageBase64 } },
        { text: buildPrompt(correction, locale) }
      ]
    }],
    generationConfig: {
      responseMimeType: 'application/json',
      responseSchema: RESPONSE_SCHEMA,
      temperature: 0.2,
      maxOutputTokens: 4096
    }
  };

  let res;
  try {
    res = await fetchImpl(`${ENDPOINT_BASE}${encodeURIComponent(getModel())}:generateContent`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'x-goog-api-key': apiKey },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(60000)
    });
  } catch {
    // A timeout or DNS failure is ours, not the photographer's, and the call
    // is refunded on the way out.
    throw new AnalysisError('upstream_unreachable',
      'Could not reach the analysis service. Try again in a moment.', 503);
  }

  if (res.status === 429) {
    throw new AnalysisError('rate_limited', 'Too many photos at once. Try again shortly.', 429);
  }
  if (!res.ok) {
    let detail = `status ${res.status}`;
    try {
      const j = await res.json();
      if (j?.error?.message) detail = j.error.message;
    } catch { /* the status alone will have to do */ }
    throw new AnalysisError('upstream_error', `Analysis failed: ${detail}`, 502);
  }

  const json = await res.json();
  const text = json?.candidates?.[0]?.content?.parts?.[0]?.text;
  if (!text) {
    throw new AnalysisError('empty_response', 'The analysis came back empty. Try another photo.', 502);
  }

  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch {
    try {
      parsed = JSON.parse(text.replace(/```json\s*|\s*```/g, '').trim());
    } catch {
      throw new AnalysisError('unparseable', 'The analysis could not be read. Try again.', 502);
    }
  }

  const u = json.usageMetadata || {};
  return {
    raw: parsed,
    usage: {
      promptTokens: u.promptTokenCount ?? null,
      outputTokens: u.candidatesTokenCount ?? null
    },
    model: getModel()
  };
}
