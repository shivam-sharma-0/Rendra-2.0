Return only one JSON object {"questions": [...], "spec": {...}} for this software project.
questions: 0 to 2 blocking questions, only what a sensible production default cannot decide (the
core purpose or main features when unclear, who uses it when that changes the product); [] when
the request is clear. Never ask about visual style, storage, database, backend or sign-in, and never
use platform_ keys. Each question has key, prompt, required (boolean), recommended (option id) and
options (id, label, description), including an option with id "other".
For a named product such as a Todo app, CRM, booking app, store or dashboard, its primary use case is
already known. Do not ask the user to repeat it, choose basic versus complete, list obvious features,
choose pages/frameworks, or confirm standard persistence. Infer sensible complete product defaults.
Questions must identify an actual missing business rule with materially different implementations.
Do not ask generic discovery questions. If none block implementation, return questions: [].
Generic settings cover ordinary product preferences. Do not invent account deletion, password rotation,
billing or other account lifecycle workflows unless the user explicitly requests them.

spec: what to build, assuming each question's recommended option. Fields: title, language (e.g. "en"),
goal; pages [{id, path ("/" first), title, featureIds}]; entities [{id, fields [{name, kind:
STRING|NUMBER|BOOLEAN|DATE, required}], ownership: USER|SHARED}] only for data the user saves;
features [{id, kind, description, pageIds, entityIds}] with kind CONTENT, CONTACT_LINK, NAV_ANCHOR,
CALCULATOR, COUNTER, LOCAL_CRUD, CONTACT_FORM or a short custom UPPER_CASE kind. CONTACT_FORM means
an actual submitted contact form with success/error feedback; CONTACT_LINK means a mailto/tel/external link.
LOCAL_CRUD means records can be created, edited and deleted with reload persistence, and references the
corresponding entity in entityIds; do not use it for read-only lists. Tags, labels and categories are a field of
the record they organize, set in its create/edit form and filtered on its list; do not add a separate entity, page or
CRUD feature for them unless the user explicitly asks to manage them separately. theme {background, foreground,
accent, muted (hex colors), radius, spacing (like "8px"), font: system|serif|mono}; acceptance
[{id, featureId, kind: "BROWSER", steps [{kind: CLICK|FILL|SELECT|ASSERT_TEXT|ASSERT_COUNT|
ASSERT_ROUTE|RELOAD, selector, value}], expected}] with at least one scenario per feature.
Use at most 24 browser actions across all acceptance scenarios combined, not 24 per scenario;
combine redundant steps while preserving feature coverage, and never truncate required checks.
constraints {responsive: true, accessibility: true, exclusions: []}. Ids are letters, digits and _.
Page featureIds and feature pageIds must agree.

Include capabilities {"persistence":"NONE|BROWSER|SERVER", "accounts":boolean} in spec.
Infer the product's actual data needs, even when no database or framework is named: customer bookings,
orders, shared business records and multi-user collaboration require SERVER persistence. Personal record
apps also default to SERVER persistence; BROWSER is reserved for explicit local-only, offline-only,
frontend-only or disposable demo requests. Display-only sites need NONE. Respect explicit browser-only/demo
scope, and never invent accounts where they are unnecessary. This proposal does not select a provider,
prove a connection, grant permissions or authorize schema changes.

Plan the complete publishable product, not a demo screen. Include the pages, navigation and supporting
workflows appropriate to this particular request, including detail/edit/settings/auth pages where needed;
do not pad a calculator or explicit static page with irrelevant routes. Persistent products need real
backend/API/data behavior, never localStorage as an undeclared substitute. Preserve explicit scope limits.
For personal/private records, propose accounts and ownership unless the user explicitly excludes sign-in;
public/shared content can use no accounts when appropriate. Include loading, empty, validation/error,
success and responsive accessible states. Describe each feature concretely enough to implement it.

User prompt:
{{request}}
