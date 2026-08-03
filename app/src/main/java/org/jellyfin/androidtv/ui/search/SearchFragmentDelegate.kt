package org.jellyfin.androidtv.ui.search

import android.content.Context
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.Row
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.integration.canopy.seerr.SeerrEntry
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.CustomListRowPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.ui.seerr.navigateToSeerrEntry
import org.jellyfin.androidtv.ui.seerr.seerrListRow

internal class SearchFragmentDelegate(
	private val context: Context,
	private val backgroundService: BackgroundService,
	private val itemLauncher: ItemLauncher,
	private val navigationRepository: NavigationRepository,
) {
	val rowsAdapter = MutableObjectAdapter<Row>(CustomListRowPresenter())

	private var seerrRow: ListRow? = null
	private var seerrEntries: List<SeerrEntry> = emptyList()

	fun showResults(searchResultGroups: Collection<SearchResultGroup>) {
		rowsAdapter.clear()
		seerrRow = null
		val adapters = mutableListOf<ItemRowAdapter>()
		for ((labelRes, baseItems) in searchResultGroups) {
			val adapter = ItemRowAdapter(
				context,
				baseItems.toList(),
				CardPresenter(),
				rowsAdapter,
				QueryType.Search
			).apply {
				setRow(ListRow(HeaderItem(context.getString(labelRes)), this))
			}
			adapters.add(adapter)
		}
		for (adapter in adapters) adapter.Retrieve()
		updateSeerrRow()
	}

	fun showSeerrResults(entries: List<SeerrEntry>) {
		seerrEntries = entries
		updateSeerrRow()
	}

	/**
	 * Keeps the Seerr row as the last row of the adapter. The library rows
	 * above it are rebuilt independently by [showResults].
	 */
	private fun updateSeerrRow() {
		seerrRow?.let { rowsAdapter.remove(it) }
		seerrRow = null

		if (seerrEntries.isEmpty()) return

		val row = seerrListRow(context.getString(R.string.canopy_seerr_search_row), seerrEntries)
		seerrRow = row
		rowsAdapter.add(row)
	}

	val onItemViewClickedListener = OnItemViewClickedListener { _, item, _, row ->
		if (navigationRepository.navigateToSeerrEntry(item)) return@OnItemViewClickedListener
		if (item !is BaseRowItem) return@OnItemViewClickedListener
		val adapter = (row as? ListRow)?.adapter as? ItemRowAdapter ?: return@OnItemViewClickedListener
		itemLauncher.launch(item, adapter, context)
	}

	val onItemViewSelectedListener = OnItemViewSelectedListener { _, item, _, _ ->
		val baseItem = (item as? BaseRowItem)?.baseItem
		if (baseItem != null) {
			backgroundService.setBackground(baseItem)
		} else {
			backgroundService.clearBackgrounds()
		}
	}
}
