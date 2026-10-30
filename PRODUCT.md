# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Product Purpose

Separately installable WearTAK MOPP plugin, application ID `com.weartak.plugin.mopp`.
The operator taps None or MOPP 0 through 4 to save immediately through WearTAK.

## Operating Context

Native Wear OS interface following the user-confirmed LACE Wear Compose conventions.
WearTAK is the sole authority for saved PPE and SELF_STATUS serialization.

## Capabilities and Constraints

None is distinct from MOPP 0. A never-saved selection is distinct from both.
Retain the saved selection when inactive and stop contributing while inactive.
Preserve legacy unknown timestamps. Never replay cached selections, stamp receipt
time, or mutate raw CoT, chat, or markers. Compatibility requires a typed shared SDK
capability; otherwise show Update WearTAK. Hardware acceptance belongs to the coordinator.
Always-on publishing requires a matching WearTAK host change: report enabled=true,
accept saves with legacy false or unset enable state, and normalize setEnabled(false)
to true without changing selection or timestamp. The plugin never enables publishing
on read; until the host change lands, this behavior is not shipped.
The host derives the menu subtitle from authoritative selection: display-only None
for an unset selection, None for explicit None, and compact MOPP0 through MOPP4 for
saved levels. Never persist the display fallback.

## Product Principles

- Every explicit selection tap requests an immediate durable host commit.
- Checked selection reflects only acknowledged host state, never an optimistic draft.
- The host owns durable state, revision checks, timestamps, and live contribution.
- A live compatible plugin with a saved selection publishes status; unset selection never contributes.
- Missing or incompatible plugin stops reporting without clearing saved data.
- Failed, conflicted, disconnected, and unsupported states are explicit.
- Native watch affordances take precedence over visual novelty.
