package org.jellyfin.androidtv.ui.itemdetail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.integration.canopy.CanopyRefreshTarget
import org.jellyfin.androidtv.ui.GridButton

class CanopyItemDetailRefreshTests : FunSpec({
	test("both declared refresh targets are dispatched independently") {
		var itemRefreshes = 0
		var surfaceRefreshes = 0

		dispatchCanopyRefresh(
			targets = setOf(CanopyRefreshTarget.JELLYFIN_ITEM, CanopyRefreshTarget.ITEM_DETAIL_SURFACE),
			onJellyfinItem = { itemRefreshes++ },
			onItemDetailSurface = { surfaceRefreshes++ },
		)

		itemRefreshes shouldBe 1
		surfaceRefreshes shouldBe 1
	}

	test("an empty target set performs no refresh") {
		var refreshes = 0

		dispatchCanopyRefresh(emptySet(), { refreshes++ }, { refreshes++ })

		refreshes shouldBe 0
	}

	test("action tile ids are deterministic and collision free within the visible row") {
		val first = (0 until 5).map(::canopyActionTileId)
		val second = (0 until 5).map(::canopyActionTileId)

		first.distinct().size shouldBe 5
		second shouldContainExactly first
	}

	test("grid buttons keep compact visible text separate from accessibility detail") {
		val button = GridButton(1, "Label", null, "Label. Bounded description")

		button.text shouldBe "Label"
		button.contentDescription shouldBe "Label. Bounded description"
		GridButton(2, "Default label").contentDescription shouldBe "Default label"
	}
})
