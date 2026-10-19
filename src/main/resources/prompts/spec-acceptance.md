Render the contract's data-testid hooks on the real semantic elements described by acceptanceTargets.
Use space-separated data-testid tokens when one element has multiple targets. Preserve meaningful original ids/roles/attributes.
State selectors such as :checked must reflect real native state, not hard-coded test results or hidden duplicate elements.
Link checks assert href without visiting external/mailto/fragment destinations; internal route flows still navigate.
The compiled acceptance is authoritative when an original selector suggests a different destination.
