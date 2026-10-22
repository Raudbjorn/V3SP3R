# Provider-aware settings

Five separate configurations are available: OpenRouter, Gemini, OpenAI, xAI and Anthropic. Credentials are encrypted under separate names; each provider retains its model selection. Saving a key or model captures the provider at the time the UI event occurs. Requests read a single selection snapshot rather than combining independently changing key/model/provider streams.

OpenAI-compatible requests choose the correct URL, credential, attribution headers and token-limit field. Model fallback remains within the selected provider. OpenRouter remains the default provider. Speech chooses the provider's backend and voice set, and Anthropic correctly reports that speech is unavailable.

The Anthropic configuration is defined here, but native Anthropic chat is implemented by the separate native-provider PR. Until that is integrated, selecting Anthropic returns an explicit error; it never sends Anthropic credentials to an OpenAI-compatible endpoint.

The generic protocol boundary is the extension point for native providers. HTTP mocks cover compatible-provider requests; live provider account access and device audio playback have not been tested. Catalog defaults are conservative identifiers rather than unverified future generations from the donor. The OpenRouter subset was checked against its public model catalog on 2026-10-22.

Integration: the separate secure-storage PR overlaps SettingsStore's legacy OpenRouter migration. Keep the synchronous encrypted write/readback and delete legacy plaintext only after the write succeeds. The useful-model-catalog PR supplies stricter live OpenRouter capability filtering; preserve that parser when combining the catalog additions here.
