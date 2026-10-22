# Provider-aware settings

Five separate configurations are available: OpenRouter, Gemini, OpenAI, xAI and Anthropic. Credentials are encrypted under separate names; each provider retains its model selection. Saving a key or model captures the provider at the time the UI event occurs. Requests read a single selection snapshot rather than combining independently changing key/model/provider streams.

OpenAI-compatible requests choose the correct URL, credential, attribution headers and token-limit field. Model fallback remains within the selected provider. OpenRouter remains the default provider. Speech chooses the provider's backend and voice set, and Anthropic correctly reports that speech is unavailable.

Anthropic chat uses its native Messages API with provider-specific authentication, tool blocks, tool results, and image encoding. Anthropic speech remains unavailable. [Native Anthropic PR #11](https://github.com/Raudbjorn/V3SP3R/pull/11) supplies this implementation.

The generic protocol boundary is the extension point for native providers. HTTP mocks cover compatible-provider requests; live provider account access and device audio playback have not been tested. Catalog defaults are conservative identifiers rather than unverified future generations from the donor. The OpenRouter subset was checked against its public model catalog on 2026-10-22.

Integration: [secure-storage PR #16](https://github.com/Raudbjorn/V3SP3R/pull/16) overlaps SettingsStore's legacy OpenRouter migration. Keep the synchronous encrypted write/readback and delete legacy plaintext only after the write succeeds. [Useful-model-catalog PR #9](https://github.com/Raudbjorn/V3SP3R/pull/9) supplies stricter live OpenRouter capability filtering; preserve that parser when combining the catalog additions here.

Credential reads migrate legacy plaintext under a mutex on the IO dispatcher. Deletion stores a verified empty secure value before removing plaintext, preventing interrupted cleanup from resurrecting a key. Secure preferences are excluded from backups. Signal analysis uses the same provider client as chat. Speech calls cancel their network reads when stopped, enforce bounded response/audio sizes, parse RIFF chunks with padding, and drain queued PCM before releasing playback.
