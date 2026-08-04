# Features

What the Canopy integration looks like on a TV. Every capture below is from a
release build on an NVIDIA Shield against a live Jellyfin 12 + Canopy + Seerr
server.

Nothing here appears when the server has no Canopy plugin, has Seerr disabled,
or your account is not linked to Seerr — the surfaces are omitted entirely
rather than showing empty states or dead buttons.

---

## Discover

A **Discover** entry appears in the toolbar beside Home and Search, but only
once the server confirms Seerr is reachable and linked for you.

![Home screen with the Discover toolbar entry](media/screenshots/01-home-toolbar.jpg)

It opens rows of your watchlist, what's trending, and popular and upcoming
movies and series.

![Discover screen showing trending and popular rows](media/screenshots/02-discover-rows.jpg)

Further down are genre tiles, each opening a paged grid for that genre.

![Genre tiles on the Discover screen](media/screenshots/03-discover-genres.jpg)

<video src="media/videos/discover-browse.mp4" controls muted loop width="100%"></video>

## Requesting something you don't have

Selecting anything that is not in your library opens a details screen built
from the same components as the native one — poster, rating, genres, overview
— with the request actions inline.

![Seerr detail screen with a Request action](media/screenshots/04-seerr-detail.jpg)

Series show a season picker offering exactly the seasons you are missing, and
4K requests consider the 4K state separately. If your Seerr account has a
request quota, the button shows how many you have left.

<video src="media/videos/seerr-detail.mp4" controls muted loop width="100%"></video>

## Cast and filmographies

Every title carries its cast, and each person opens their filmography split
into movies and series, ordered by popularity.

![Cast row on a Seerr title](media/screenshots/05-seerr-cast-row.jpg)

![Person screen with More from rows split by movies and series](media/screenshots/06-person-filmography.jpg)

The **native** Jellyfin person screen gains the same rows, so an actor's full
filmography is browsable even when your library only holds a few of their
titles.

## Search

Search results gain a "Discover · Seerr" row underneath your library results —
movies, series and people — ending in a tile that opens the full Discover
screen.

![Search results with the Discover · Seerr row](media/screenshots/07-search-seerr-row.jpg)

## Canopy actions on your own library

Spoiler Guard, Hidden Content and Seerr actions appear on item details as
ordinary buttons beside Play and Watched. They participate in the same
overflow rules as the built-in actions, so the row never outgrows its width.

![Item details with Canopy action buttons](media/screenshots/08-item-canopy-buttons.jpg)

<video src="media/videos/canopy-actions.mp4" controls muted loop width="100%"></video>

Selecting one opens the form the *server* described, rendered with native
controls. The client does not know what Spoiler Guard is — it renders whatever
the catalog offers, so new server features appear without a client update.

![Spoiler Guard configuration dialog](media/screenshots/09-spoiler-guard-dialog.jpg)

Long-pressing a Seerr card for a title already in your library opens the same
actions without leaving the row.

## Settings

Everything is switchable under **Settings → Canopy**.

![Canopy settings screen](media/screenshots/10-settings-canopy.jpg)

*Action placement* decides where the actions live: with the item buttons, in
the *Other options* menu, or in a dedicated Actions row below the details.

![Action placement options](media/screenshots/11-settings-placement.jpg)
