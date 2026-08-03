package org.jellyfin.androidtv.ui.search

import android.content.Context
import androidx.leanback.widget.ArrayObjectAdapter
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

internal class SearchFragmentDelegate(
	private val context: Context,
	private val backgroundService: BackgroundService,
	private val itemLauncher: ItemLauncher,
	private val navigationRepository: NavigationRepository,
	/** Long-press handler for Seerr cards; supplied by the hosting fragment. */
	var onLongPress: ((SeerrEntry) -> Boolean)? = null,
) {
	val rowsAdapter = MutableObjectAdapter<Row>(CustomListRowPresenter())

	// The Seerr row and its adapter are deliberately persistent: rebuilding the
	// row while one of its cards holds focus detaches the focused view and the
	// next d-pad event crashes FocusFinder (#4). Items are updated in place.
	private val seerrAdapter = ArrayObjectAdapter(SeerrCardPresenter(onLongPress))
	private val seerrRow = ListRow(HeaderItem(context.getString(R.string.canopy_seerr_search_row)), seerrAdapter)
	private var seerrEntries: List<SeerrEntry> = emptyList()

	fun showResults(searchResultGroups: Collection<SearchResultGroup>) {
		// Diff the rows instead of clearing them. A removed row detaches its
		// views, and if focus is inside one, the next key event walks a view
		// that is no longer in the hierarchy and crashes focus traversal
		// (#4, and again in Compose's embedded-view focus search). Rows keyed
		// by header survive a new result set as the same row.
		val adapters = mutableListOf<ItemRowAdapter>()
		val rows = mutableListOf<Row>()
		for ((labelRes, baseItems) in searchResultGroups) {
			val row = ListRow(HeaderItem(context.getString(labelRes)), null)
			val adapter = ItemRowAdapter(
				context,
				baseItems.toList(),
				CardPresenter(),
				rowsAdapter,
				QueryType.Search
			)
			val listRow = ListRow(row.headerItem, adapter)
			adapter.setRow(listRow)
			adapters.add(adapter)
			rows.add(listRow)
		}
		if (seerrEntries.isNotEmpty()) rows.add(seerrRow)

		rowsAdapter.replaceAll(
			rows,
			areItemsTheSame = { old, new -> old.rowHeaderKey() == new.rowHeaderKey() },
			// Row contents are owned by each row's own adapter, so a row that
			// keeps its identity never needs a rebind from here.
			areContentsTheSame = { old, new -> old.rowHeaderKey() == new.rowHeaderKey() },
		)
		for (adapter in adapters) adapter.Retrieve()
		updateSeerrRow()
	}

	private fun Row.rowHeaderKey(): String? = (this as? ListRow)?.headerItem?.name

	fun showSeerrResults(entries: List<SeerrEntry>) {
		seerrEntries = entries
		updateSeerrRow()
	}

	/**
	 * Keeps the Seerr row as the last row of the adapter, updating the
	 * persistent adapter's items in place. The library rows above it are
	 * rebuilt independently by [showResults].
	 */
	private fun updateSeerrRow() {
		if (seerrEntries.isEmpty()) {
			rowsAdapter.remove(seerrRow)
			seerrAdapter.clear()
			return
		}

		seerrAdapter.setItems(seerrEntries, SEERR_DIFF)
		if (rowsAdapter.indexOf(seerrRow) < 0) rowsAdapter.add(seerrRow)
	}

	/** Clears every row; used when the query is emptied. */
	fun clearResults() {
		seerrEntries = emptyList()
		seerrAdapter.clear()
		rowsAdapter.clear()
	}

	val onItemViewClickedListener = OnItemViewClickedListener { _, item, _, row ->
		if (navigationRepository.navigateToSeerrEntry(item)) return@OnItemViewClickedListener
		if (item !is BaseRowItem) return@OnItemViewClickedListener
		val adapter = (row as? ListRow)?.adapter as? ItemRowAdapter ?: return@OnItemViewClickedListener
		itemLauncher.launch(item, adapter, context)
	}

	private companion object {
		private val SEERR_DIFF = object : androidx.leanback.widget.DiffCallback<SeerrEntry>() {
			override fun areItemsTheSame(oldItem: SeerrEntry, newItem: SeerrEntry): Boolean = oldItem == newItem
			override fun areContentsTheSame(oldItem: SeerrEntry, newItem: SeerrEntry): Boolean = oldItem == newItem
		}
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
