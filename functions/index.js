// Bitey AI's server half.
//
// The Android app has no account and no server of its own, so this is the only
// place where "has this person paid?" can be answered truthfully. The client's
// own answer is useful for drawing the interface and is worth nothing here:
// every call re-verifies the purchase with Google Play before a photograph
// reaches the model.
//
// What crosses this boundary is one photograph and, if the person typed one, a
// correction in their own words -- plus their diet, only if they turned that on
// in Settings. Never the diary, never the rest of the profile, never a backup. What is stored is a number per subscription per day.

import { buildAnalyse } from './analyse.js';

export const analyse = buildAnalyse();
