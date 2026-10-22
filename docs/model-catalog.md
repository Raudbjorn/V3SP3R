# Useful tool model catalog and visible failures

The live OpenRouter picker includes every model advertising `tools`, retains multiple models per manufacturer, skips malformed entries and labels image support. Rate-limited/experimental aliases are excluded. Successful live results are not padded with unverified static defaults.

On 2026-10-22 the public OpenRouter catalog showed that the previous default `nousresearch/hermes-4-405b` did not advertise tool support. Several previous fallback IDs were absent or lacked tools. The offline set now contains only IDs advertising tools in that check, with `anthropic/claude-sonnet-4.5` as the default. Existing explicit user selections are retained. If the active ID is absent, the picker labels it unavailable with unverified capabilities; this display-only entry is not added to the verified live catalog. Fallback advances only for recognized availability/tool-use failures and the known excluded non-tool ID. A model that accepts the request and returns ordinary text can succeed without fallback; capability detection is not guaranteed for arbitrary persisted selections.

The picker searches IDs and names across the complete catalog and displays at most 40 results. Refine the search to reach models beyond the first page; the active choice is first when search is empty.

Blank answers, malformed tool responses and excessive tool calls fail visibly instead of creating an empty assistant message. Finish reasons distinguish output exhaustion and provider refusal. The shared system prompt also treats tool output as untrusted data rather than instructions or approval.

Validation is offline/unit-based except the unauthenticated public catalog GET. No live model completion was sent.
