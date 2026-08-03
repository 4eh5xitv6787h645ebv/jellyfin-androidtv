package org.jellyfin.androidtv.integration.canopy

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import okhttp3.Call
import okhttp3.EventListener
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

		CanopyPlatformRoutes.exactRelative(HttpMethod.POST, "/JellyfinCanopy/Platform/v1/discovery") shouldBe null
		CanopyPlatformRoutes.exactRelative(HttpMethod.GET, "/JellyfinCanopy/Platform/v1/discovery") shouldBe
			CanopyPlatformRoutes.discovery
		CanopyPlatformRoutes.exactRelative(HttpMethod.GET, "/prefix/JellyfinCanopy/Platform/v1/discovery") shouldBe null
		CanopyPlatformRoutes.exactRelative(HttpMethod.GET, "/JellyfinCanopy/Platform/v1/discovery/") shouldBe null
	}

	test("real SDK composition bounds all five routes at root and configured base paths") {
		listOf(
			"" to "https://example.invalid",
			"/jellyfin" to "https://example.invalid/jellyfin",
			"/my%20jellyfin" to "https://example.invalid/my%20jellyfin",
			"/percent%25segment" to "https://example.invalid/percent%25segment",
		).forEach { (expectedPrefix, baseUrl) ->
			val observedPaths = mutableListOf<String>()
			val requestRegistry = CanopyRequestRegistry()
			val responseProvider = Interceptor { chain ->
				observedPaths += chain.request().url.encodedPath
				response(
					chain.request(),
					200,
					"x".repeat(CanopyContractBounds.MAX_RESOLVE_BYTES + 20),
				)
			}
			val base = OkHttpClient.Builder()
				.eventListenerFactory(requestRegistry.eventListenerFactory())
				.addInterceptor(CanopyResponseBoundingInterceptor(requestRegistry))
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
			val transport = ApiClientCanopyTransport(api, requestRegistry)

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

	test("unregistered Platform terminals reject while non-terminal near matches pass through") {
		val nearMatches = listOf(
			"POST" to "/jellyfin/JellyfinCanopy/Platform/v1/discovery",
			"GET" to "/jellyfin/JellyfinCanopy/Platform/v1/discovery/suffix",
			"GET" to "/JellyfinCanopy/JellyfinCanopy/Platform/v1/discovery",
			"GET" to "/%4AellyfinCanopy/JellyfinCanopy/Platform/v1/discovery",
			"GET" to "/jellyfin//JellyfinCanopy/Platform/v1/discovery",
			"GET" to "/jellyfin/jellyfincanopy/Platform/v1/discovery",
			"GET" to "/jellyfin/%4AellyfinCanopy/Platform/v1/discovery",
			"GET" to "/jellyfin/JellyfinCanopy%2FPlatform/v1/discovery",
			"GET" to "/%252e%252e/JellyfinCanopy/Platform/v1/discovery",
			"GET" to "/%252F/JellyfinCanopy/Platform/v1/discovery",
			"GET" to "/%255c/JellyfinCanopy/Platform/v1/discovery",
			"GET" to "/%254AellyfinCanopy/JellyfinCanopy/Platform/v1/discovery",
			"GET" to "/%25252e%25252e/JellyfinCanopy/Platform/v1/discovery",
			"GET" to "/percent%25segment/JellyfinCanopy/Platform/v1/discovery",
		)

		nearMatches.forEach { (method, path) ->
			val request = request(method, path)
			request.url.encodedPath shouldBe path
			val upstream = response(request, 200, "x".repeat(CanopyContractBounds.MAX_RESOLVE_BYTES + 20))

			if (CanopyPlatformRoutes.hasReviewedTerminal(method, path)) {
				shouldThrow<CanopyUnregisteredRequestException> {
					CanopyResponseBoundingInterceptor().intercept(chain(request, upstream))
				}.message shouldBe CanopyUnregisteredRequestException.MESSAGE
			} else {
				CanopyResponseBoundingInterceptor().intercept(chain(request, upstream)) shouldBe upstream
			}
		}
	}

	test("an exact Platform URL is bounded only while its transport registration is live") {
		val route = CanopyPlatformRoutes.discovery
		val request = request(route.method.name, "/percent%25segment${route.encodedPath}")
		val upstream = response(request, 200, "bounded")
		val requestRegistry = CanopyRequestRegistry()
		val interceptor = CanopyResponseBoundingInterceptor(requestRegistry)
		val registeredClient = bindingClient(requestRegistry)

		shouldThrow<CanopyUnregisteredRequestException> {
			interceptor.intercept(chain(request, upstream))
		}
		requestRegistry.withRegistration(route.method, request.url, route) { }
		shouldThrow<CanopyUnregisteredRequestException> {
			interceptor.intercept(chain(request, upstream))
		}

		val bounded = requestRegistry.withRegistration(route.method, request.url, route) {
			val call = registeredClient.newCall(request)
			interceptor.intercept(chain(request, upstream, call))
		}
		bounded.header(CanopyResponseBoundingInterceptor.BOUNDED_HEADER) shouldBe
			CanopyResponseBoundingInterceptor.BOUNDED_HEADER_VALUE

		shouldThrow<CanopyUnregisteredRequestException> {
			interceptor.intercept(chain(request, upstream))
		}
	}

	test("an unregistered identical call cannot steal an exact live Call binding") {
		val route = CanopyPlatformRoutes.negotiate
		val request = request(route.method.name, "${route.encodedPath}?protocolMinimum=1&protocolMaximum=1")
		val requestRegistry = CanopyRequestRegistry()
		val interceptor = CanopyResponseBoundingInterceptor(requestRegistry)
		val registeredClient = bindingClient(requestRegistry)

		requestRegistry.withRegistration(route.method, request.url, route) {
			val registeredCall = registeredClient.newCall(request)
			shouldThrow<CanopyUnregisteredRequestException> {
				interceptor.intercept(chain(request, response(request, 200, "steal")))
			}
			interceptor.intercept(chain(request, response(request, 200, "bounded"), registeredCall))
				.header(CanopyResponseBoundingInterceptor.BOUNDED_HEADER) shouldBe
				CanopyResponseBoundingInterceptor.BOUNDED_HEADER_VALUE
		}
	}

	test("one registration cannot authorize a second SDK Call") {
		val route = CanopyPlatformRoutes.discovery
		val request = request(route.method.name, route.encodedPath)
		val requestRegistry = CanopyRequestRegistry()
		val interceptor = CanopyResponseBoundingInterceptor(requestRegistry)
		val registeredClient = bindingClient(requestRegistry)

		requestRegistry.withRegistration(route.method, request.url, route) {
			val first = registeredClient.newCall(request)
			val second = registeredClient.newCall(request)
			interceptor.intercept(chain(request, response(request, 200, "first"), first))
				.header(CanopyResponseBoundingInterceptor.BOUNDED_HEADER) shouldBe
				CanopyResponseBoundingInterceptor.BOUNDED_HEADER_VALUE
			shouldThrow<CanopyUnregisteredRequestException> {
				interceptor.intercept(chain(request, response(request, 200, "second"), second))
			}
		}
	}

	test("two identical concurrent registrations bind distinct Calls while a third is rejected") {
		val route = CanopyPlatformRoutes.negotiate
		val request = request(route.method.name, route.encodedPath)
		val requestRegistry = CanopyRequestRegistry()
		val interceptor = CanopyResponseBoundingInterceptor(requestRegistry)
		val registeredClient = bindingClient(requestRegistry)

		coroutineScope {
			val release = CompletableDeferred<Unit>()
			val ready = List(2) { CompletableDeferred<Call>() }
			val jobs = ready.map { signal ->
				async {
					requestRegistry.withRegistration(route.method, request.url, route) {
						signal.complete(registeredClient.newCall(request))
						release.await()
					}
				}
			}
			val calls = ready.map { it.await() }
			try {
				calls.forEachIndexed { index, call ->
					interceptor.intercept(chain(request, response(request, 200, "bounded-$index"), call))
						.header(CanopyResponseBoundingInterceptor.BOUNDED_HEADER) shouldBe
						CanopyResponseBoundingInterceptor.BOUNDED_HEADER_VALUE
				}
				shouldThrow<CanopyUnregisteredRequestException> {
					interceptor.intercept(chain(request, response(request, 200, "extra")))
				}
			} finally {
				release.complete(Unit)
				jobs.awaitAll()
			}
		}
	}

	test("cancellation and request drift remove or reject only the exact Call binding") {
		val route = CanopyPlatformRoutes.discovery
		val request = request(route.method.name, route.encodedPath)
		val requestRegistry = CanopyRequestRegistry()
		val interceptor = CanopyResponseBoundingInterceptor(requestRegistry)
		val registeredClient = bindingClient(requestRegistry)

		coroutineScope {
			val cancelledReady = CompletableDeferred<Call>()
			val survivingReady = CompletableDeferred<Call>()
			val release = CompletableDeferred<Unit>()
			val cancelled = async {
				requestRegistry.withRegistration(route.method, request.url, route) {
					cancelledReady.complete(registeredClient.newCall(request))
					release.await()
				}
			}
			val surviving = async {
				requestRegistry.withRegistration(route.method, request.url, route) {
					survivingReady.complete(registeredClient.newCall(request))
					release.await()
				}
			}
			val cancelledCall = cancelledReady.await()
			val survivingCall = survivingReady.await()
			try {
				cancelled.cancelAndJoin()
				shouldThrow<CanopyUnregisteredRequestException> {
					interceptor.intercept(chain(request, response(request, 200, "cancelled"), cancelledCall))
				}
				shouldThrow<CanopyUnregisteredRequestException> {
					interceptor.intercept(chain(request, response(request, 200, "shift-steal")))
				}
				interceptor.intercept(chain(request, response(request, 200, "surviving"), survivingCall))
					.header(CanopyResponseBoundingInterceptor.BOUNDED_HEADER) shouldBe
					CanopyResponseBoundingInterceptor.BOUNDED_HEADER_VALUE
			} finally {
				release.complete(Unit)
				surviving.await()
			}
		}

		requestRegistry.withRegistration(route.method, request.url, route) {
			val wrong = request.newBuilder().url(request.url.newBuilder().addPathSegment("wrong").build()).build()
			val mismatchedCall = registeredClient.newCall(wrong)
			shouldThrow<CanopyUnregisteredRequestException> {
				interceptor.intercept(chain(wrong, response(wrong, 200, "wrong"), mismatchedCall))
			}
		}

		requestRegistry.withRegistration(route.method, request.url, route) {
			val boundCall = registeredClient.newCall(request)
			val mutated = request.newBuilder().url(request.url.newBuilder().addPathSegment("mutated").build()).build()
			shouldThrow<CanopyUnregisteredRequestException> {
				interceptor.intercept(chain(mutated, response(mutated, 200, "mutated"), boundCall))
			}
		}
	}

	test("the Call binding preserves an existing EventListener factory") {
		val route = CanopyPlatformRoutes.discovery
		val request = request(route.method.name, route.encodedPath)
		val requestRegistry = CanopyRequestRegistry()
		val delegated = AtomicInteger()
		val delegate = EventListener.Factory {
			delegated.incrementAndGet()
			EventListener.NONE
		}
		val client = OkHttpClient.Builder()
			.eventListenerFactory(requestRegistry.eventListenerFactory(delegate))
			.build()

		requestRegistry.withRegistration(route.method, request.url, route) { client.newCall(request) }
		delegated.get() shouldBe 1
	}

	test("the transport refuses unreviewed paths and mismatched response bounds before SDK dispatch") {
		val api = mockk<org.jellyfin.sdk.api.client.ApiClient>()
		val transport = ApiClientCanopyTransport(api, CanopyRequestRegistry())

		shouldThrow<IllegalArgumentException> {
			transport.request(HttpMethod.GET, "/Items", emptyMap(), null, 32)
		}
		shouldThrow<IllegalArgumentException> {
			transport.request(
				CanopyPlatformRoutes.discovery.method,
				CanopyPlatformRoutes.discovery.encodedPath,
				emptyMap(),
				null,
				CanopyPlatformRoutes.discovery.maximumResponseBytes + 1,
			)
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

		val bounded = interceptRegistered(route, request, upstream)

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
		val requestRegistry = CanopyRequestRegistry()
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
			.eventListenerFactory(requestRegistry.eventListenerFactory())
			.addInterceptor(CanopyResponseBoundingInterceptor(requestRegistry))
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
		val client = CanopyClient(ApiClientCanopyTransport(api, requestRegistry))

		val boundedDuplicate = ApiClientCanopyTransport(api, requestRegistry).request(
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
			interceptRegistered(route, request, upstream)
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
			interceptRegistered(route, request, upstream)
		}

		error.status shouldBe 503
		error.body.decodeToString() shouldBe body
		error.headers shouldContainExactly mapOf("content-type" to listOf("application/json"))
		error.message shouldBe CanopyBoundedResponseException.MESSAGE
	}

	test("SDK 1.8.12 wraps the exact interceptor exception and the transport recovers it") {
		var authenticatedRequestObserved = false
		val requestRegistry = CanopyRequestRegistry()
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
			.eventListenerFactory(requestRegistry.eventListenerFactory())
			.addInterceptor(CanopyResponseBoundingInterceptor(requestRegistry))
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

		val result = ApiClientCanopyTransport(api, requestRegistry).request(
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
		val route = CanopyPlatformRoutes.discovery
		every { api.webSocket } returns mockk()
		every { api.baseUrl } returns "https://example.invalid"
		every {
			api.createUrl(route.encodedPath, emptyMap(), emptyMap(), false)
		} returns "https://example.invalid${route.encodedPath}"
		io.mockk.coEvery {
			api.request(any(), any(), any(), any(), any())
		} throws ApiClientException("SDK failure", IOException("ordinary failure"))

		shouldThrow<ApiClientException> {
			ApiClientCanopyTransport(api).request(
				route.method,
				route.encodedPath,
				emptyMap(),
				null,
				route.maximumResponseBytes,
			)
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

private val unregisteredClient = OkHttpClient()

private fun bindingClient(requestRegistry: CanopyRequestRegistry) = OkHttpClient.Builder()
	.eventListenerFactory(requestRegistry.eventListenerFactory())
	.build()

private fun chain(
	request: Request,
	response: Response,
	call: Call = unregisteredClient.newCall(request),
) = mockk<Interceptor.Chain>().also { chain ->
	every { chain.request() } returns request
	every { chain.call() } returns call
	every { chain.proceed(any()) } returns response
}

private suspend fun interceptRegistered(
	route: CanopyPlatformRoute,
	request: Request,
	response: Response,
): Response {
	val requestRegistry = CanopyRequestRegistry()
	val client = bindingClient(requestRegistry)
	return requestRegistry.withRegistration(route.method, request.url, route) {
		val call = client.newCall(request)
		CanopyResponseBoundingInterceptor(requestRegistry).intercept(chain(request, response, call))
	}
}
