#!/usr/bin/env bash
set -euo pipefail

evidence_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$evidence_dir"

sha256sum --check SHA256SUMS
if rg --quiet '://|jc_arradmin|0389d76cd968' raw; then
  printf 'Evidence contains an unredacted private runtime identifier.\n' >&2
  exit 1
fi

contains() {
  local file=$1
  local value=$2
  rg --fixed-strings --quiet "$value" "raw/$file"
}

omits() {
  local file=$1
  local value=$2
  ! contains "$file" "$value"
}

contains canopy-final-hidden-form.xml 'text="Hide this item (required)"'
contains canopy-final-hidden-form.xml 'checked="false" clickable="true" enabled="true" focusable="true" focused="true"'
contains canopy-final-alpha-hidden.xml 'Hidden Content: hidden (all surfaces)'
omits canopy-final-home-hidden.xml 'Alpha Adventure'
contains canopy-final-alpha-unhidden.xml 'Hidden Content: visible'
contains canopy-final-home-restored.xml 'Alpha Adventure'

contains canopy-final-seerr-form.xml 'text="Submit this request? (required)"'
contains canopy-final-seerr-form.xml 'focused="true"'
contains canopy-final-delta-requested.xml 'Seerr: Standard pending'
omits canopy-final-delta-requested.xml 'content-desc="Request with Seerr.'

contains canopy-final-provider-offline.xml 'Hidden Content: visible'
omits canopy-final-provider-offline.xml 'Seerr:'
contains canopy-final-provider-recovered.xml 'Seerr: available to request'
contains canopy-final-platform-disabled.xml 'content-desc="Play"'
omits canopy-final-platform-disabled.xml 'Actions · Spoiler Guard'
contains canopy-final-platform-restored.xml 'Seerr: available to request'

contains canopy-final2-spoiler-enable-form.xml 'text="Protect this item (required)"'
contains canopy-final2-spoiler-enable-form.xml 'checked="false" clickable="true" enabled="true" focusable="true" focused="true"'
contains canopy-final2-guard-protected.xml 'Spoiler Guard: protected'
contains canopy-final2-guard-content.xml 'Season 1, Episode 1'
omits canopy-final2-guard-content.xml 'The Secret of Chapter 1.1'
omits canopy-final2-guard-unprotected-content.xml 'Season 1, Episode 1'
contains canopy-final2-guard-unprotected-content.xml 'The Secret of Chapter 1.1'

contains canopy-final2-back-form.xml 'text="Configure Hidden Content"'
omits canopy-final2-back-return.xml 'text="Configure Hidden Content"'
contains canopy-final2-back-return.xml 'Hidden Content: visible'

contains canopy-final2-host-offline.sanitized.xml "text=\"Who's watching?\""
contains canopy-final2-host-offline.sanitized.xml '[redacted-disposable-server]'
contains canopy-final2-host-offline.sanitized.xml '[redacted-test-user]'
contains canopy-final2-host-offline.sanitized.xml '[redacted-disposable-server-id]'
contains canopy-final2-host-recovered.xml 'Seerr: available to request'
contains canopy-final2-base-session.xml 'content-desc="Play"'
omits canopy-final2-base-session.xml 'Actions · Spoiler Guard'
contains canopy-final2-upgraded-head.xml 'Seerr: available to request'
contains canopy-final3-catalog.xml 'Actions · Spoiler Guard: unprotected · Hidden Content: visible · Seerr: available to request'

jq -e '
  .schemaVersion == 1 and
  ([.scenarios[]] | all(. == "pass")) and
  .finalState.platformEnabled == true and
  .finalState.providerHealthy == true and
  .finalState.alphaVisible == true and
  .finalState.guardProtected == false
' results.json >/dev/null

printf 'Canopy native emulator evidence: PASS\n'
