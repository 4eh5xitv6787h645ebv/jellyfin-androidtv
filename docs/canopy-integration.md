# Canopy integration

This fork is the first native adopter of the [Jellyfin Canopy](https://github.com/4eh5xitv6787h645ebv/Jellyfin-Canopy)
server plugin. The integration has two halves with deliberately different
transports.

## 1. Extension platform (item detail actions)

`integration/canopy/` speaks the frozen Canopy Platform v1 protocol
(`/JellyfinCanopy/Platform/v1/...`: discovery → negotiate → resolve → prepare →
invoke). It renders whatever the server's catalog offers for an item — today
Spoiler Guard, Hidden Content and Seerr request actions — as an **Actions** row
on the item details screen, with server-defined forms shown as native dialogs.
Nothing feature-specific is hardcoded; new server contributions appear without
client changes as long as they stay within the negotiated schema.

- Renderer/controller: `ui/itemdetail/CanopyItemDetailController.kt`
- Protocol client and hardening: `integration/canopy/` (allowlisted routes,
  bounded responses, strict wire validation)
- User toggle: Settings → Canopy → *Item detail actions*

## 2. Seerr discovery (proxy surface)

Platform v1 has no search/discovery surface (Canopy ADR-0012 scopes v1 to the
item-detail pilot), so the discovery experience consumes the same authorized
legacy proxy routes the Canopy web client uses (`/JellyfinCanopy/seerr/...`).
All requests ride the session-bound SDK `ApiClient` on `Dispatchers.IO`; the
client never handles a Seerr credential and every read degrades to graceful
omission — when Seerr is unreachable, disabled, or the user is not linked, the
surfaces simply do not appear.

Surfaces (`integration/canopy/seerr/` + `ui/seerr/` + `ui/search/`):

- **Discover** toolbar screen: Your watchlist, Trending, Popular/Upcoming
  movies and series, and genre tiles that open paged grids.
- **Search**: a "Discover · Seerr" row (movies, series, people) below library
  results, with a *Discover more* tail tile.
- **Seerr item details**: poster, combined critic/audience/IMDb ratings,
  overview, request status; *Request* / *Request in 4K* actions with a season
  picker for series (offered only when the Seerr server allows partial
  requests) and remaining-quota display; Cast, *Part of <collection>*,
  Similar, Recommended and *More from <studio/network>* rows. Items already in
  the library open the regular Jellyfin details screen instead.
- **Person screen**: biography plus movie/series credit rows.
- User toggle: Settings → Canopy → *Seerr search suggestions* (governs the
  search row, the Discover button and all Seerr screens' entry points).

## Testing

- Unit tests: `app/src/test/kotlin/integration/canopy/` (platform) and
  `.../integration/canopy/seerr/` (proxy mapping, request bodies, status
  handling).
- Scripted UI E2E: [`e2e/canopy-seerr/`](../e2e/canopy-seerr/README.md) drives
  a signed-in device through every surface and button.
- Platform pilot evidence: `docs/canopy-native-evidence/`.

## Known sharp edges

- The SDK's raw `ApiClient.request()` executes on the calling dispatcher —
  always wrap calls in `Dispatchers.IO` (a release build on hardware turns
  this mistake into `NetworkOnMainThreadException`; debug builds tolerate it).
- Leanback + async content: never rebuild a focused `ListRow` in place, and
  claim focus after asynchronously populating a screen (see issue #4).
