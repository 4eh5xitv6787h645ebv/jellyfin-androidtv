package org.jellyfin.androidtv.integration.canopy.seerr

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.RawResponse
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import java.util.UUID

class SeerrRepositoryTests : FunSpec({
	fun apiWith(vararg responses: Pair<String, String>): ApiClient {
		val apiClient = mockk<ApiClient>()
		every { apiClient.accessToken } returns "token"
		for ((path, body) in responses) {
			coEvery {
				apiClient.request(HttpMethod.GET, path, emptyMap(), any(), null)
			} returns RawResponse(body.encodeToByteArray(), 200, emptyMap())
		}
		return apiClient
	}

	test("search maps movies, series and people and drops collections") {
		val repository = SeerrRepository(
			apiWith(
				"/JellyfinCanopy/seerr/search" to """
					{
						"page": 1, "totalPages": 1, "totalResults": 4,
						"results": [
							{"id": 550, "mediaType": "movie", "title": "Fight Club", "releaseDate": "1999-10-15",
							 "posterPath": "/abc.jpg", "mediaInfo": {"status": 5, "jellyfinMediaId": "0b67e975-99cb-4bf3-96d3-b13ec50365ff"}},
							{"id": 1399, "mediaType": "tv", "name": "Game of Thrones", "firstAirDate": "2011-04-17"},
							{"id": 287, "mediaType": "person", "name": "Brad Pitt", "profilePath": "/pit.jpg"},
							{"id": 10, "mediaType": "collection", "name": "Star Wars Collection"}
						]
					}
				""".trimIndent(),
			),
		)

		val results = repository.search("test")

		results.size shouldBe 3
		val movie = results[0] as SeerrDiscoverItem
		movie.tmdbId shouldBe 550L
		movie.title shouldBe "Fight Club"
		movie.year shouldBe 1999
		movie.posterUrl shouldBe "https://image.tmdb.org/t/p/w400/abc.jpg"
		movie.status shouldBe SeerrMediaStatus.AVAILABLE
		movie.jellyfinMediaId shouldBe UUID.fromString("0b67e975-99cb-4bf3-96d3-b13ec50365ff")

		val series = results[1] as SeerrDiscoverItem
		series.mediaType shouldBe SeerrMediaType.TV
		series.title shouldBe "Game of Thrones"
		series.year shouldBe 2011
		series.status shouldBe SeerrMediaStatus.NOT_REQUESTED

		val person = results[2] as SeerrPersonItem
		person.personId shouldBe 287L
		person.name shouldBe "Brad Pitt"
		person.profileUrl shouldBe "https://image.tmdb.org/t/p/w400/pit.jpg"
	}

	test("capabilities requires active and linked") {
		val repository = SeerrRepository(
			apiWith(
				"/JellyfinCanopy/seerr/user-status" to
					"""{"active": true, "userFound": true, "canRequest4kMovie": true, "canRequest4kTv": false}""",
			),
		)

		val capabilities = repository.capabilities()
		capabilities.available shouldBe true
		capabilities.canRequest4kMovie shouldBe true
		capabilities.canRequest4kTv shouldBe false
	}

	test("capabilities degrades to unavailable on failure") {
		val apiClient = mockk<ApiClient>()
		every { apiClient.accessToken } returns "token"
		coEvery {
			apiClient.request(HttpMethod.GET, any(), emptyMap(), any(), null)
		} throws InvalidStatusException(404)

		SeerrRepository(apiClient).capabilities().available shouldBe false
	}

	test("details maps seasons, per-season statuses, cast and runtime fallback") {
		val repository = SeerrRepository(
			apiWith(
				"/JellyfinCanopy/seerr/tv/1399" to """
					{
						"id": 1399, "name": "Game of Thrones", "overview": "Winter is coming.",
						"firstAirDate": "2011-04-17", "episodeRunTime": [57],
						"voteAverage": 8.4,
						"genres": [{"id": 18, "name": "Drama"}],
						"seasons": [
							{"seasonNumber": 0, "name": "Specials", "episodeCount": 14},
							{"seasonNumber": 1, "name": "Season 1", "episodeCount": 10},
							{"seasonNumber": 2, "name": "Season 2", "episodeCount": 10}
						],
						"credits": {"cast": [{"id": 22970, "name": "Peter Dinklage", "character": "Tyrion", "profilePath": "/p.jpg"}]},
						"mediaInfo": {"status": 4, "seasons": [{"seasonNumber": 1, "status": 5}]}
					}
				""".trimIndent(),
			),
		)

		val details = repository.details(SeerrMediaType.TV, 1399).shouldNotBeNull()

		details.item.title shouldBe "Game of Thrones"
		details.item.status shouldBe SeerrMediaStatus.PARTIALLY_AVAILABLE
		details.runtimeMinutes shouldBe 57
		details.communityRating shouldBe 8.4f
		details.genres shouldContainExactly listOf("Drama")
		details.seasons.map { it.number } shouldContainExactly listOf(1, 2)
		details.seasons[0].status shouldBe SeerrMediaStatus.AVAILABLE
		details.seasons[1].status shouldBe SeerrMediaStatus.NOT_REQUESTED
		details.cast.single().role shouldBe "Tyrion"
	}

	test("submitRequest sends movie body and maps conflict to AlreadyRequested") {
		val apiClient = mockk<ApiClient>()
		every { apiClient.accessToken } returns "token"
		val body = slot<Any>()
		coEvery {
			apiClient.request(HttpMethod.POST, "/JellyfinCanopy/seerr/request", emptyMap(), emptyMap(), capture(body))
		} returns RawResponse("{}".encodeToByteArray(), 201, emptyMap())

		val repository = SeerrRepository(apiClient)
		val item = SeerrDiscoverItem(
			tmdbId = 550,
			mediaType = SeerrMediaType.MOVIE,
			title = "Fight Club",
			year = 1999,
			posterUrl = null,
			status = SeerrMediaStatus.NOT_REQUESTED,
			status4k = SeerrMediaStatus.NOT_REQUESTED,
			jellyfinMediaId = null,
		)

		repository.submitRequest(item, is4k = false) shouldBe SeerrRequestOutcome.Submitted

		val payload = body.captured as JsonObject
		payload.getValue("mediaType").jsonPrimitive.content shouldBe "movie"
		payload.getValue("mediaId").jsonPrimitive.content shouldBe "550"
		payload["seasons"].shouldBeNull()
		payload["is4k"].shouldBeNull()

		coEvery {
			apiClient.request(HttpMethod.POST, "/JellyfinCanopy/seerr/request", emptyMap(), emptyMap(), any())
		} throws InvalidStatusException(409)

		repository.submitRequest(item, is4k = false) shouldBe SeerrRequestOutcome.AlreadyRequested
	}

	test("submitSeasonRequest sends explicit season list") {
		val apiClient = mockk<ApiClient>()
		every { apiClient.accessToken } returns "token"
		val body = slot<Any>()
		coEvery {
			apiClient.request(HttpMethod.POST, "/JellyfinCanopy/seerr/request", emptyMap(), emptyMap(), capture(body))
		} returns RawResponse("{}".encodeToByteArray(), 201, emptyMap())

		SeerrRepository(apiClient).submitSeasonRequest(1399, listOf(1, 3), is4k = true) shouldBe SeerrRequestOutcome.Submitted

		val payload = body.captured as JsonObject
		payload.getValue("mediaType").jsonPrimitive.content shouldBe "tv"
		payload.getValue("seasons").jsonArray.map { it.jsonPrimitive.content } shouldContainExactly listOf("1", "3")
		payload.getValue("is4k").jsonPrimitive.content shouldBe "true"
	}

	test("genres map slider entries with backdrops and drop unnamed entries") {
		val repository = SeerrRepository(
			apiWith(
				"/JellyfinCanopy/seerr/discover/genreslider/movie" to """
					[
						{"id": 28, "name": "Action", "backdrops": ["/a.jpg"]},
						{"id": 12, "name": "", "backdrops": []},
						{"name": "No id"}
					]
				""".trimIndent(),
			),
		)

		val genres = repository.genres(SeerrMediaType.MOVIE)

		genres.size shouldBe 1
		genres[0].genreId shouldBe 28L
		genres[0].backdropUrl shouldBe "https://image.tmdb.org/t/p/w780/a.jpg"
	}

	test("discoverByGenre reports paging") {
		val repository = SeerrRepository(
			apiWith(
				"/JellyfinCanopy/seerr/discover/movies/genre/28" to """
					{"page": 1, "totalPages": 3, "totalResults": 60,
					 "results": [{"id": 603, "mediaType": "movie", "title": "The Matrix", "releaseDate": "1999-03-30"}]}
				""".trimIndent(),
			),
		)

		val page = repository.discoverByGenre(SeerrMediaType.MOVIE, 28, 1)

		page.hasMore shouldBe true
		page.items.single().title shouldBe "The Matrix"
	}

	test("malformed poster paths are not turned into urls") {
		val repository = SeerrRepository(
			apiWith(
				"/JellyfinCanopy/seerr/search" to """
					{"results": [{"id": 1, "mediaType": "movie", "title": "X",
					 "posterPath": "/../../etc/passwd.jpg"}]}
				""".trimIndent(),
			),
		)

		(repository.search("x").single() as SeerrDiscoverItem).posterUrl.shouldBeNull()
	}

	test("watchlist entries resolve tmdbId and render as discover items") {
		val repository = SeerrRepository(
			apiWith(
				"/JellyfinCanopy/seerr/watchlist" to """
					{"page": 1, "totalPages": 1, "totalResults": 1,
					 "results": [{"tmdbId": 27205, "mediaType": "movie", "title": "Inception"}]}
				""".trimIndent(),
			),
		)

		repository.watchlist().single().tmdbId shouldBe 27205L
	}

	test("details expose collection, studio and network references") {
		val repository = SeerrRepository(
			apiWith(
				"/JellyfinCanopy/seerr/movie/121" to """
					{"id": 121, "title": "The Two Towers", "releaseDate": "2002-12-18",
					 "collection": {"id": 119, "name": "The Lord of the Rings Collection"},
					 "productionCompanies": [{"id": 12, "name": "New Line Cinema"}],
					 "networks": []}
				""".trimIndent(),
			),
		)

		val details = repository.details(SeerrMediaType.MOVIE, 121).shouldNotBeNull()
		details.collection shouldBe SeerrNamedRef(119, "The Lord of the Rings Collection")
		details.studio shouldBe SeerrNamedRef(12, "New Line Cinema")
		details.network.shouldBeNull()
	}

	test("collection parts map and combined ratings parse") {
		val repository = SeerrRepository(
			apiWith(
				"/JellyfinCanopy/seerr/collection/119" to """
					{"id": 119, "name": "LOTR", "parts": [
						{"id": 120, "mediaType": "movie", "title": "The Fellowship of the Ring", "releaseDate": "2001-12-19"}
					]}
				""".trimIndent(),
				"/JellyfinCanopy/seerr/movie/120/ratingscombined" to
					"""{"rt": {"criticsScore": 91.0, "audienceScore": 95.0}, "imdb": {"criticsScore": 8.9}}""",
			),
		)

		repository.collectionParts(119).single().title shouldBe "The Fellowship of the Ring"

		val item = SeerrDiscoverItem(120, SeerrMediaType.MOVIE, "x", null, null,
			SeerrMediaStatus.NOT_REQUESTED, SeerrMediaStatus.NOT_REQUESTED, null)
		val ratings = repository.ratings(item)
		ratings.rtCritics shouldBe 91
		ratings.rtAudience shouldBe 95
		ratings.imdb shouldBe 8.9
	}

	test("quota only surfaces restricted buckets and settings default open") {
		val repository = SeerrRepository(
			apiWith(
				"/JellyfinCanopy/seerr/quota" to
					"""{"movie": {"limit": 5, "remaining": 2, "restricted": true}, "tv": {"restricted": false}}""",
				"/JellyfinCanopy/seerr/settings/partial-requests" to
					"""{"partialRequestsEnabled": false, "enableSpecialEpisodes": false}""",
			),
		)

		repository.quota(SeerrMediaType.MOVIE)?.remaining shouldBe 2
		repository.quota(SeerrMediaType.TV).shouldBeNull()
		repository.partialRequestsEnabled() shouldBe false
	}

	test("media status wire mapping") {
		SeerrMediaStatus.fromWire(null) shouldBe SeerrMediaStatus.NOT_REQUESTED
		SeerrMediaStatus.fromWire(1) shouldBe SeerrMediaStatus.NOT_REQUESTED
		SeerrMediaStatus.fromWire(2) shouldBe SeerrMediaStatus.PENDING
		SeerrMediaStatus.fromWire(3) shouldBe SeerrMediaStatus.PROCESSING
		SeerrMediaStatus.fromWire(4) shouldBe SeerrMediaStatus.PARTIALLY_AVAILABLE
		SeerrMediaStatus.fromWire(5) shouldBe SeerrMediaStatus.AVAILABLE
		SeerrMediaStatus.fromWire(6) shouldBe SeerrMediaStatus.BLOCKED
		SeerrMediaStatus.fromWire(7) shouldBe SeerrMediaStatus.NOT_REQUESTED
	}
})
