package org.jellyfin.androidtv.integration.canopy

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo

class CanopyResponseBoundingInterceptorTests : FunSpec({
	test("the interceptor allowlist pins exactly five method and encoded-path pairs") {
		CanopyPlatformRoutes.all.map { Triple(it.method, it.encodedPath, it.maximumResponseBytes) } shouldContainExactly listOf(
			Triple(HttpMethod.GET, "/JellyfinCanopy/Platform/v1/discovery", CanopyContractBounds.MAX_ACTION_BYTES),
			Triple(HttpMethod.GET, "/JellyfinCanopy/Platform/v1/negotiate", CanopyContractBounds.MAX_ACTION_BYTES),
			Triple(
				HttpMethod.POST,
				"/JellyfinCanopy/Platform/v1/surfaces/item-detail/resolve",
				CanopyContractBounds.MAX_RESOLVE_BYTES,
			),
			Triple(HttpMethod.POST, "/JellyfinCanopy/Platform/v1/actions/prepare", CanopyContractBounds.MAX_ACTION_BYTES),
			Triple(HttpMethod.POST, "/JellyfinCanopy/Platform/v1/actions/invoke", CanopyContractBounds.MAX_ACTION_BYTES),
		)

		CanopyPlatformRoutes.exact("POST", "/JellyfinCanopy/Platform/v1/discovery") shouldBe null
		CanopyPlatformRoutes.exact("GET", "/prefix/JellyfinCanopy/Platform/v1/discovery") shouldBe
			CanopyPlatformRoutes.discovery
		CanopyPlatformRoutes.exact("GET", "/JellyfinCanopy/Platform/v1/discovery/") shouldBe null
	}

	test("real SDK composition bounds all five routes at root and configured base paths") {
		listOf(
			"" to "https://example.invalid",
			"/jellyfin" to "https://example.invalid/jellyfin",
			"/my%20jellyfin" to "https://example.invalid/my%20jellyfin",
		).forEach { (expectedPrefix, baseUrl) ->
			val observedPaths = mutableListOf<String>()
			val responseProvider = Interceptor { chain ->
				observedPaths += chain.request().url.encodedPath
				response(
					chain.request(),
					200,
					"x".repeat(CanopyContractBounds.MAX_RESOLVE_BYTES + 20),
				)
			}
			val base = OkHttpClient.Builder()
				.addInterceptor(CanopyResponseBoundingInterceptor())
				.addInterceptor(responseProvider)
				.build()
			val factory = OkHttpFactory(base)
			val api = factory.create(
				baseUrl,
				"test-access-token",
				ClientInfo("Canopy prefix test", "1"),
				DeviceInfo("test-device", "Test device"),
				HttpClientOptions(),
				factory,
			)
			val transport = ApiClientCanopyTransport(api)

			CanopyPlatformRoutes.all.forEach { route ->
				val result = transport.request(
					route.method,
					route.encodedPath,
					mapOf("query-is-irrelevant" to "1"),
					null,
					route.maximumResponseBytes,
				)

				result.bodyReadMode shouldBe CanopyBodyReadMode.BOUNDED_DURING_READ
				result.body.size shouldBe route.maximumResponseBytes + 1
			}

			observedPaths shouldContainExactly CanopyPlatformRoutes.all.map { expectedPrefix + it.encodedPath }
		}
	}

	test("prefix matcher rejects method suffix duplicate slash encoding and case near-matches") {
		val nearMatches = listOf(
			"POST" to "/jellyfin/JellyfinCanopy/Platform/v1/discovery",
			"GET" to "/jellyfin/JellyfinCanopy/Platform/v1/discovery/suffix",
			"GET" to "/JellyfinCanopy/JellyfinCanopy/Platform/v1/discovery",
			"GET" to "/%4AellyfinCanopy/JellyfinCanopy/Platform/v1/discovery",
			"GET" to "/jellyfin//JellyfinCanopy/Platform/v1/discovery",
			"GET" to "/jellyfin/jellyfincanopy/Platform/v1/discovery",
			"GET" to "/jellyfin/%4AellyfinCanopy/Platform/v1/discovery",
			"GET" to "/jellyfin/JellyfinCanopy%2FPlatform/v1/discovery",
		)

		nearMatches.forEach { (method, path) ->
			CanopyPlatformRoutes.exact(method, path) shouldBe null
			val request = request(method, path)
			val upstream = response(request, 200, "x".repeat(CanopyContractBounds.MAX_RESOLVE_BYTES + 20))

			CanopyResponseBoundingInterceptor().intercept(chain(request, upstream)) shouldBe upstream
		}
	}

	test("an unrelated response passes through untouched") {
		val request = request("GET", "/Items")
		val upstream = response(request, 200, "ordinary Jellyfin response")
		val chain = chain(request, upstream)

		CanopyResponseBoundingInterceptor().intercept(chain) shouldBe upstream
	}

	test("a successful Platform response is bounded rebuilt and strips unrelated headers") {
		val route = CanopyPlatformRoutes.discovery
		val request = request(route.method.name, route.encodedPath)
		val upstream = response(
			request,
			200,
			"""{"Available":true}""",
			mapOf(
				"Content-Type" to "application/json",
				"ETag" to "\"sha256-${"a".repeat(64)}\"",
				"X-Server-Secret" to "must-not-cross-the-boundary",
			),
		)

		val bounded = CanopyResponseBoundingInterceptor().intercept(chain(request, upstream))

		bounded.body!!.string() shouldBe """{"Available":true}"""
		bounded.headers.toMultimap() shouldContainExactly mapOf(
			"content-type" to listOf("application/json"),
			"etag" to listOf("\"sha256-${"a".repeat(64)}\""),
			CanopyResponseBoundingInterceptor.BOUNDED_HEADER.lowercase() to listOf("1"),
		)
	}

	test("SDK boundary rejects repeated protocol fields and retains one canonical strong ETag") {
		val canonical = "\"sha256-${"c".repeat(64)}\""
		val secondStrong = "\"sha256-${"d".repeat(64)}\""
		var etags = listOf(canonical, secondStrong)
		var contentTypes = listOf("application/json")
		val responseProvider = Interceptor { chain ->
			val headers = Headers.Builder().apply {
				contentTypes.forEach { add("Content-Type", it) }
				etags.forEach { add("ETag", it) }
			}.build()
			response(
				chain.request(),
				200,
				"""{"Available":true,"ProtocolMinimum":1,"ProtocolMaximum":1}""",
				headers,
			)
		}
		val base = OkHttpClient.Builder()
			.addInterceptor(CanopyResponseBoundingInterceptor())
			.addInterceptor(responseProvider)
			.build()
		val factory = OkHttpFactory(base)
		val api = factory.create(
			"https://example.invalid",
			"test-access-token",
			ClientInfo("Canopy header test", "1"),
			DeviceInfo("test-device", "Test device"),
			HttpClientOptions(),
			factory,
		)
		val client = CanopyClient(api)

		val boundedDuplicate = ApiClientCanopyTransport(api).request(
			HttpMethod.GET,
			CanopyPlatformRoutes.discovery.encodedPath,
			emptyMap(),
			null,
			CanopyPlatformRoutes.discovery.maximumResponseBytes,
		)
		boundedDuplicate.headers.entries.single { (name) -> name.equals("ETag", ignoreCase = true) }
			.value shouldContainExactly etags
		val repeatedEtag = client.discover() as CanopyCallResult.Success
		repeatedEtag.etag shouldBe null

		etags = listOf(canonical)
		val singleEtag = client.discover() as CanopyCallResult.Success
		singleEtag.etag shouldBe canonical

		contentTypes = listOf("application/json", "application/json")
		client.discover() shouldBe CanopyCallResult.Failure(CanopyFailureKind.INVALID_RESPONSE, 200)
	}

	test("an oversized Platform body stops at route limit plus one and redacts throwable rendering") {
		val route = CanopyPlatformRoutes.prepare
		val request = request(route.method.name, route.encodedPath)
		val secretTail = "must-not-appear-in-the-exception"
		val upstream = response(request, 200, "x".repeat(route.maximumResponseBytes + 20) + secretTail)

		val error = shouldThrow<CanopyBoundedResponseException> {
			CanopyResponseBoundingInterceptor().intercept(chain(request, upstream))
		}

		error.status shouldBe 200
		error.body.size shouldBe route.maximumResponseBytes + 1
		error.message shouldBe CanopyBoundedResponseException.MESSAGE
		error.toString().contains(secretTail) shouldBe false
	}

	test("a non-success response carries only bounded protocol data with a constant message") {
		val route = CanopyPlatformRoutes.invoke
		val request = request(route.method.name, route.encodedPath)
		val body = """{"Error":true,"Message":"Provider unavailable"}"""
		val upstream = response(
			request,
			503,
			body,
			mapOf("Content-Type" to "application/json", "X-Internal" to "secret"),
		)

		val error = shouldThrow<CanopyBoundedResponseException> {
			CanopyResponseBoundingInterceptor().intercept(chain(request, upstream))
		}

		error.status shouldBe 503
		error.body.decodeToString() shouldBe body
		error.headers shouldContainExactly mapOf("content-type" to listOf("application/json"))
		error.message shouldBe CanopyBoundedResponseException.MESSAGE
	}

	test("SDK 1.8.12 wraps the exact interceptor exception and the transport recovers it") {
		var authenticatedRequestObserved = false
		val responseProvider = Interceptor { chain ->
			authenticatedRequestObserved = chain.request().header("Authorization")?.isNotBlank() == true
			response(
				chain.request(),
				503,
				"""{"Error":true,"Code":"unavailable"}""",
				mapOf("Content-Type" to "application/json"),
			)
		}
		val base = OkHttpClient.Builder()
			.addInterceptor(CanopyResponseBoundingInterceptor())
			.addInterceptor(responseProvider)
			.build()
		val factory = OkHttpFactory(base)
		val api = factory.create(
			"https://example.invalid",
			"test-access-token",
			ClientInfo("Canopy transport test", "1"),
			DeviceInfo("test-device", "Test device"),
			HttpClientOptions(),
			factory,
		)

		val result = ApiClientCanopyTransport(api).request(
			HttpMethod.POST,
			CanopyPlatformRoutes.invoke.encodedPath,
			emptyMap(),
			null,
			CanopyPlatformRoutes.invoke.maximumResponseBytes,
		)

		authenticatedRequestObserved shouldBe true
		result.status shouldBe 503
		result.body.decodeToString() shouldBe """{"Error":true,"Code":"unavailable"}"""
		result.bodyReadMode shouldBe CanopyBodyReadMode.BOUNDED_DURING_READ
	}

	test("the transport does not unwrap a different SDK IOException") {
		val api = mockk<org.jellyfin.sdk.api.client.ApiClient>()
		every { api.webSocket } returns mockk()
		io.mockk.coEvery {
			api.request(any(), any(), any(), any(), any())
		} throws ApiClientException("SDK failure", IOException("ordinary failure"))

		shouldThrow<ApiClientException> {
			ApiClientCanopyTransport(api).request(HttpMethod.GET, "/not-canopy", emptyMap(), null, 32)
		}
	}
})

private fun request(method: String, encodedPath: String) = Request.Builder()
	.url("https://example.invalid$encodedPath")
	.method(method, if (method == "GET") null else byteArrayOf().toRequestBody())
	.build()

private fun response(
	request: Request,
	status: Int,
	body: String,
	headers: Map<String, String> = mapOf("Content-Type" to "text/plain"),
) = response(request, status, body, headers.toHeaders())

private fun response(
	request: Request,
	status: Int,
	body: String,
	headers: Headers,
) = Response.Builder()
	.request(request)
	.protocol(Protocol.HTTP_1_1)
	.code(status)
	.message("Test response")
	.headers(headers)
	.body(body.toResponseBody(headers["Content-Type"]?.toMediaType()))
	.build()

private fun chain(request: Request, response: Response) = mockk<Interceptor.Chain>().also { chain ->
	every { chain.request() } returns request
	every { chain.proceed(request) } returns response
}
